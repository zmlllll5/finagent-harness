package com.persephone.finagent.edgar;

import com.persephone.finagent.config.EdgarProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thin, well-behaved HTTP client for SEC EDGAR.
 *
 * <p>Responsibilities kept deliberately narrow: rate limit, retry, cache, and
 * identify us honestly. Business logic lives above this layer.
 *
 * <p>Three operational details matter and are easy to get wrong:
 * <ul>
 *   <li><b>User-Agent.</b> SEC blocks requests without a descriptive UA
 *       containing contact info. Omitting it produces 403s that look like
 *       application bugs.</li>
 *   <li><b>Rate limiting.</b> SEC's fair-access policy caps automated traffic at
 *       10 req/s and will temporarily ban offenders. We self-throttle at a
 *       configurable lower rate.</li>
 *   <li><b>Caching.</b> Filing data is immutable once accepted, so caching is
 *       both safe and essential — an analyst iterating on a query should not
 *       re-hit EDGAR for the same filing 50 times.</li>
 * </ul>
 */
@Component
public class EdgarClient {

    private static final Logger log = LoggerFactory.getLogger(EdgarClient.class);

    private static final String COMPANY_CONCEPT_URL =
            "https://data.sec.gov/api/xbrl/companyconcept/CIK%s/%s/%s.json";
    private static final String COMPANY_FACTS_URL =
            "https://data.sec.gov/api/xbrl/companyfacts/CIK%s.json";
    private static final String SUBMISSIONS_URL =
            "https://data.sec.gov/submissions/CIK%s.json";
    private static final String FILING_INDEX_URL =
            "https://www.sec.gov/Archives/edgar/data/%s/%s/%s-index.htm";

    private final EdgarProperties props;
    private final HttpClient httpClient;

    /** Simple token-bucket-ish pacing: enforce a minimum gap between calls. */
    private final AtomicLong lastRequestNanos = new AtomicLong(0L);

    public EdgarClient(EdgarProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String companyConceptUrl(String paddedCik, String taxonomy, String tag) {
        return String.format(COMPANY_CONCEPT_URL, paddedCik, taxonomy, tag);
    }

    public String companyFactsUrl(String paddedCik) {
        return String.format(COMPANY_FACTS_URL, paddedCik);
    }

    public String submissionsUrl(String paddedCik) {
        return String.format(SUBMISSIONS_URL, paddedCik);
    }

    /**
     * Canonical human-openable URL for a filing index page.
     *
     * @param cik             unpadded or padded CIK
     * @param accessionNumber e.g. 0001045810-24-000029
     */
    public static String filingIndexUrl(String cik, String accessionNumber) {
        String cikDigits = cik.replaceAll("[^0-9]", "").replaceFirst("^0+(?!$)", "");
        String accnNoDashes = accessionNumber.replace("-", "");
        return String.format(FILING_INDEX_URL, cikDigits, accnNoDashes, accessionNumber);
    }

    /**
     * GET with caching, throttling and retry.
     *
     * @param url          absolute URL
     * @param cacheKey     stable key; also the on-disk filename stem
     * @return body, or empty when the resource is unavailable (404 is a normal
     *         outcome here — not every company tags every concept)
     */
    public Optional<String> get(String url, String cacheKey) {
        Optional<String> cached = readCache(cacheKey);
        if (cached.isPresent()) {
            log.debug("cache hit: {}", cacheKey);
            return cached;
        }

        throttle();

        IOException lastError = null;
        for (int attempt = 1; attempt <= props.getRetryAttempts(); attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(props.getTimeoutMs()))
                        .header("User-Agent", props.getUserAgent())
                        .header("Accept-Encoding", "gzip")
                        .header("Accept", "application/json, text/html, */*")
                        .GET()
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofByteArray());

                int code = response.statusCode();
                String body = decode(response);

                if (code == 200) {
                    writeCache(cacheKey, body);
                    return Optional.of(body);
                }
                if (code == 404) {
                    log.debug("not found (404), treating as absent: {}", url);
                    // Negative-cache briefly by writing a sentinel so repeated
                    // misses during one session do not re-hit the network.
                    writeCache(cacheKey, "__NOT_FOUND__");
                    return Optional.empty();
                }
                if (code == 403 || code == 429) {
                    // Rate limited or UA rejected. Back off progressively.
                    long backoffMs = 1000L * attempt * attempt;
                    log.warn("EDGAR returned {}; backing off {}ms (attempt {}/{})",
                            code, backoffMs, attempt, props.getRetryAttempts());
                    sleep(backoffMs);
                } else {
                    log.warn("EDGAR returned HTTP {} for {}", code, url);
                    sleep(500L * attempt);
                }
            } catch (IOException | InterruptedException e) {
                lastError = (e instanceof IOException) ? (IOException) e : null;
                log.warn("EDGAR request failed (attempt {}/{}): {}",
                        attempt, props.getRetryAttempts(), e.toString());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
                sleep(500L * attempt);
            }
        }
        log.error("EDGAR request exhausted retries: {} ({})", url,
                lastError == null ? "n/a" : lastError.getMessage());
        return Optional.empty();
    }

    // --- infrastructure -------------------------------------------------------

    /**
     * Decode a response body, transparently gunzipping when the server
     * compressed it.
     *
     * <p>Handled explicitly rather than by asking the JDK not to compress,
     * because EDGAR's JSON payloads are large — a full company-facts document
     * runs to several megabytes and compresses roughly 10:1. Disabling
     * compression to dodge this code would meaningfully slow every cold fetch.
     */
    private String decode(HttpResponse<byte[]> response) {
        byte[] raw = response.body();
        if (raw == null || raw.length == 0) return "";

        String encoding = response.headers()
                .firstValue("Content-Encoding")
                .orElse("");
        if (encoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")
                || (raw.length >= 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B)) {
            try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(raw), 8192)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                        Math.max(1024, raw.length * 4));
                byte[] buf = new byte[8192];
                int n;
                while ((n = gz.read(buf)) != -1) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.debug("gzip decode failed, treating body as plain: {}", e.getMessage());
            }
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    /**
     * Block until the configured inter-request gap has elapsed. Implemented with
     * an atomic timestamp rather than a Semaphore so the pacing is global and
     * survives concurrent callers without a warm-up penalty.
     */
    private void throttle() {
        int rps = Math.max(1, props.getRequestsPerSecond());
        long minGapNanos = 1_000_000_000L / rps;
        while (true) {
            long prev = lastRequestNanos.get();
            long now = System.nanoTime();
            long earliest = prev + minGapNanos;
            if (now >= earliest && lastRequestNanos.compareAndSet(prev, now)) {
                return;
            }
            long waitNanos = earliest - now;
            if (waitNanos > 0) {
                sleep(Math.max(1, waitNanos / 1_000_000L));
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Path cachePath(String cacheKey) {
        String dir = props.getCacheDir();
        if (dir == null || dir.isEmpty()) {
            dir = System.getProperty("user.home") + "/.finagent/cache/edgar";
        }
        dir = dir.replace("${user.home}", System.getProperty("user.home"));
        return Paths.get(dir, sanitise(cacheKey) + ".json");
    }

    private static String sanitise(String key) {
        return key.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Optional<String> readCache(String cacheKey) {
        try {
            Path p = cachePath(cacheKey);
            if (!Files.exists(p)) return Optional.empty();
            Instant mtime = Files.getLastModifiedTime(p).toInstant();
            long ageMinutes = Duration.between(mtime, Instant.now()).toMinutes();
            if (ageMinutes > props.getCacheTtlMinutes()) {
                log.debug("cache expired ({} min old): {}", ageMinutes, cacheKey);
                return Optional.empty();
            }
            String body = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            if ("__NOT_FOUND__".equals(body.trim())) {
                return Optional.empty();
            }
            return Optional.of(body);
        } catch (IOException e) {
            log.debug("cache read failed for {}: {}", cacheKey, e.getMessage());
            return Optional.empty();
        }
    }

    private void writeCache(String cacheKey, String body) {
        try {
            Path p = cachePath(cacheKey);
            Files.createDirectories(p.getParent());
            Files.write(p, body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("cache write failed for {}: {}", cacheKey, e.getMessage());
        }
    }
}
