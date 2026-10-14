package com.persephone.finagent.edgar;

import com.persephone.finagent.config.EdgarProperties;
import com.persephone.finagent.model.CompanyRef;
import com.persephone.finagent.model.FinancialFact;
import com.persephone.finagent.model.Metric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches filings and normalises them into canonical metrics.
 *
 * <p>This is the only place that talks to EDGAR for facts, and the only place
 * that knows about XBRL tag ambiguity. Everything above it works purely in terms
 * of {@link Metric} and {@link FinancialFact}.
 *
 * <p><b>Latency design.</b> A cold fetch for a company requires one request per
 * concept. With five companies and ~15 concepts that is 75 requests, which at
 * the polite rate limit takes real wall-clock time. Two mitigations:
 * the on-disk cache makes repeat queries instant, and
 * {@link #fetchSector} can warm the cache concurrently for a whole sector.
 */
@Service
public class FilingFetcher {

    private static final Logger log = LoggerFactory.getLogger(FilingFetcher.class);

    /** Unit preference when a concept reports multiple units. */
    private static final String PREFERRED_UNIT = "USD";

    private final EdgarClient client;
    private final CompanyFactsParser parser;
    private final EdgarProperties props;

    public FilingFetcher(EdgarClient client, CompanyFactsParser parser, EdgarProperties props) {
        this.client = client;
        this.parser = parser;
        this.props = props;
    }

    /**
     * Fetch every canonical metric we can for one company.
     *
     * <p>Concept failures are non-fatal: a company that does not tag {@code
     * us-gaap:Revenues} still yields its other metrics. We try each candidate
     * concept for a metric in priority order and stop at the first that returns
     * data, which is what keeps peers comparable despite taxonomy drift.
     *
     * @param metrics metrics to attempt; pass {@code Metric.values()} for all
     * @return facts keyed by metric name; absent keys mean "not reported"
     */
    public Map<String, List<FinancialFact>> fetchCompanyFacts(CompanyRef company,
                                                              List<Metric> metrics) {
        Map<String, List<FinancialFact>> result = new LinkedHashMap<>();
        String paddedCik = company.paddedCik();

        for (Metric metric : metrics) {
            if (metric.isDerived()) continue;
            List<FinancialFact> facts = fetchMetric(company, paddedCik, metric);
            if (!facts.isEmpty()) {
                result.put(metric.name(), facts);
            } else {
                log.debug("{}: no data for metric {}", company.getTicker(), metric);
            }
        }
        log.info("{}: fetched {} metric series from EDGAR", company.getTicker(), result.size());
        return result;
    }

    /**
     * Try each candidate concept for one metric, returning the first that yields
     * facts. Records in the log which concept won, so taxonomy drift is visible
     * during evaluation rather than mysterious.
     */
    private List<FinancialFact> fetchMetric(CompanyRef company, String paddedCik, Metric metric) {
        for (String concept : metric.sourceConcepts()) {
            String[] parts = splitConcept(concept);
            if (parts == null) continue;
            String taxonomy = parts[0];
            String tag = parts[1];

            String url = client.companyConceptUrl(paddedCik, taxonomy, tag);
            String cacheKey = String.format("concept_%s_%s_%s", paddedCik, taxonomy, tag);

            Optional<String> body = client.get(url, cacheKey);
            if (body.isEmpty()) continue;

            List<CompanyFactsParser.ParsedFact> parsed =
                    parser.parseConcept(body.get(), company, metric, PREFERRED_UNIT);
            if (parsed.isEmpty()) continue;

            List<FinancialFact> facts = new ArrayList<>();
            int restated = 0;
            for (CompanyFactsParser.ParsedFact pf : parsed) {
                facts.add(pf.fact);
                if (pf.restated) restated++;
            }
            if (restated > 0) {
                log.info("{} {}: {} period(s) show restated values; kept latest-filed",
                        company.getTicker(), metric, restated);
            }
            log.debug("{} {} resolved via {}/{} ({} periods)",
                    company.getTicker(), metric, taxonomy, tag, facts.size());
            return facts;
        }
        return new ArrayList<>();
    }

    /**
     * Warm the cache for a whole sector. Sequential on purpose — parallel fetching
     * would defeat the rate limiter and risks an EDGAR ban.
     */
    public Map<String, Map<String, List<FinancialFact>>> fetchSector(
            List<CompanyRef> companies, List<Metric> metrics) {
        Map<String, Map<String, List<FinancialFact>>> byCompany = new LinkedHashMap<>();
        for (CompanyRef c : companies) {
            byCompany.put(c.getTicker(), fetchCompanyFacts(c, metrics));
        }
        return byCompany;
    }

    /** Metrics worth fetching for a standard financial review. */
    public static List<Metric> defaultMetricSet() {
        List<Metric> metrics = new ArrayList<>();
        Collections.addAll(metrics,
                Metric.REVENUE,
                Metric.COST_OF_REVENUE,
                Metric.GROSS_PROFIT,
                Metric.RND_EXPENSE,
                Metric.SGNA_EXPENSE,
                Metric.OPERATING_INCOME,
                Metric.NET_INCOME,
                Metric.EPS_DILUTED,
                Metric.ASSETS,
                Metric.LIABILITIES,
                Metric.STOCKHOLDERS_EQUITY,
                Metric.CASH_AND_EQUIVALENTS,
                Metric.LONG_TERM_DEBT,
                Metric.INVENTORY,
                Metric.ACCOUNTS_RECEIVABLE,
                Metric.OPERATING_CASH_FLOW,
                Metric.CAPEX);
        return metrics;
    }

    private static String[] splitConcept(String concept) {
        if (concept == null) return null;
        int idx = concept.indexOf(':');
        if (idx <= 0) return null;
        return new String[]{concept.substring(0, idx), concept.substring(idx + 1)};
    }
}
