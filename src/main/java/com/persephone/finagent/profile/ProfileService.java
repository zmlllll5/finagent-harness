package com.persephone.finagent.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.persephone.finagent.config.ProfileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists and retrieves analyst profiles.
 *
 * <p>Storage is one JSON file per user under {@code ~/.finagent/profiles/}. Flat
 * files rather than a database on purpose: profiles are small, access is rare
 * relative to queries, and a reviewer can inspect and hand-edit them while
 * evaluating the framework. In a hosted deployment this is the seam where you
 * would swap in a real store — the interface is two methods.
 *
 * <p>Writes are atomic (temp file + move) so a crash mid-write cannot leave a
 * corrupt profile that breaks every subsequent request for that user.
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final ProfileProperties props;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, UserProfile> cache = new ConcurrentHashMap<>();

    public ProfileService(ProfileProperties props) {
        this.props = props;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Load a profile, creating an empty one on first contact. */
    public UserProfile getOrCreate(String userId) {
        String uid = normalise(userId);
        return cache.computeIfAbsent(uid, this::load);
    }

    private UserProfile load(String userId) {
        Path p = pathFor(userId);
        if (Files.exists(p)) {
            try {
                String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                UserProfile profile = mapper.readValue(json, UserProfile.class);
                if (profile.getUserId() == null) profile.setUserId(userId);
                log.debug("loaded profile for {} ({} interactions)",
                        userId, profile.getInteractions());
                return profile;
            } catch (IOException e) {
                // A corrupt profile must not take down the user's session.
                // Start clean and preserve the damaged file for diagnosis.
                log.warn("profile for {} unreadable ({}); starting fresh, old file kept at {}.bak",
                        userId, e.getMessage(), p);
                try {
                    Files.move(p, Paths.get(p.toString() + ".bak"));
                } catch (IOException ignored) { /* best effort */ }
            }
        }
        log.info("creating new profile for {}", userId);
        return new UserProfile(userId);
    }

    /** Persist a profile. Called after each interaction that taught us something. */
    public void save(UserProfile profile) {
        if (profile == null || profile.getUserId() == null) return;
        Path p = pathFor(normalise(profile.getUserId()));
        try {
            Files.createDirectories(p.getParent());
            String json = mapper.writeValueAsString(profile);
            // Atomic write: temp file in the same directory, then move.
            Path tmp = Paths.get(p.toString() + ".tmp");
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("failed to save profile for {}: {}", profile.getUserId(), e.getMessage());
        }
    }

    /** Render the profile as a short human-readable summary for the analyst. */
    public String describe(UserProfile profile) {
        if (profile == null) return "(no profile)";
        StringBuilder sb = new StringBuilder();
        sb.append("interactions=").append(profile.getInteractions());
        sb.append(", period_count=").append(profile.getPreferredPeriodCount());
        sb.append(", scale=").append(profile.getPreferredUnitScale());
        sb.append(", form=").append(profile.getPreferredForm());
        java.util.List<String> top = profile.rankedMetrics(5);
        if (!top.isEmpty()) {
            sb.append(", top_metrics=").append(String.join("/", top));
        }
        java.util.List<String> tickers = profile.rankedTickers(5);
        if (!tickers.isEmpty()) {
            sb.append(", watchlist=").append(String.join("/", tickers));
        }
        return sb.toString();
    }

    private Path pathFor(String userId) {
        String dir = props.getStoreDir();
        if (dir == null || dir.isEmpty()) {
            dir = System.getProperty("user.home") + "/.finagent/profiles";
        }
        dir = dir.replace("${user.home}", System.getProperty("user.home"));
        return Paths.get(dir, normalise(userId) + ".json");
    }

    /** Keep user ids filesystem-safe. */
    private static String normalise(String userId) {
        if (userId == null || userId.trim().isEmpty()) return "default";
        return userId.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
