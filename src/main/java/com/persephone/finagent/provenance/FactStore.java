package com.persephone.finagent.provenance;

import com.persephone.finagent.model.FinancialFact;
import com.persephone.finagent.model.Metric;
import com.persephone.finagent.model.Provenance;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory index of facts, built for the queries an analyst actually issues.
 *
 * <p>Holds the "ground truth" set that {@link com.persephone.finagent.guard.NumericGuard}
 * validates against. The critical property: <b>the guard can only ever confirm
 * facts that exist here</b>, and everything here came from EDGAR. There is no
 * path by which a model-generated number becomes "verified" without a filing
 * behind it.
 *
 * <p>Indexing strategy: primary index on (ticker, metric) for the dominant
 * access pattern (one company, several metrics, several periods), plus a
 * period-sorted view so comparison tables read naturally without re-sorting at
 * every call site.
 */
@Component
public class FactStore {

    /** ticker -> metric -> facts for that series. */
    private final Map<String, Map<String, List<FinancialFact>>> index = new ConcurrentHashMap<>();

    /** Tracks which companies have been loaded, to avoid redundant refreshes. */
    private final Map<String, Boolean> loadedCompanies = new ConcurrentHashMap<>();

    public void addFacts(String ticker, Collection<FinancialFact> facts) {
        Map<String, List<FinancialFact>> byMetric =
                index.computeIfAbsent(ticker, k -> new ConcurrentHashMap<>());
        for (FinancialFact f : facts) {
            List<FinancialFact> series = byMetric.computeIfAbsent(
                    f.getMetric(), k -> new ArrayList<>());
            // Deduplicate on (metric, period, concept): re-fetching must not
            // inflate the series or break the guard's uniqueness assumptions.
            boolean exists = series.stream().anyMatch(existing ->
                    Objects.equals(existing.getProvenance().getFiscalPeriod(),
                                   f.getProvenance().getFiscalPeriod())
                    && Objects.equals(existing.getSourceConcept(), f.getSourceConcept()));
            if (!exists) {
                series.add(f);
            }
        }
        loadedCompanies.put(ticker, Boolean.TRUE);
    }

    /** All facts for a company, flattened across metrics. */
    public List<FinancialFact> allFacts(String ticker) {
        Map<String, List<FinancialFact>> byMetric = index.get(ticker);
        if (byMetric == null) return new ArrayList<>();
        return byMetric.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /**
     * One metric series for a company, ordered chronologically.
     *
     * @param formFilter optional form type filter, e.g. "10-Q" to exclude annual
     *                   comparatives that would otherwise appear as extra columns
     */
    public List<FinancialFact> series(String ticker, Metric metric, String formFilter) {
        Map<String, List<FinancialFact>> byMetric = index.get(ticker);
        if (byMetric == null) return new ArrayList<>();
        List<FinancialFact> raw = byMetric.getOrDefault(metric.name(), new ArrayList<>());
        List<FinancialFact> filtered = new ArrayList<>();
        for (FinancialFact f : raw) {
            if (formFilter == null || formFilter.equalsIgnoreCase(f.getProvenance().getFormType())) {
                filtered.add(f);
            }
        }
        filtered.sort(Comparator
                .comparing((FinancialFact f) -> nvl(f.getProvenance().getPeriodEnd()))
                .thenComparing(f -> nvl(f.getProvenance().getFilingDate())));
        return filtered;
    }

    public List<FinancialFact> series(String ticker, Metric metric) {
        return series(ticker, metric, null);
    }

    /**
     * Find the fact matching a company/metric/period triple. Used by the guard
     * to bind a proposed number to its source.
     */
    public Optional<FinancialFact> find(String ticker, Metric metric, String period) {
        for (FinancialFact f : series(ticker, metric)) {
            if (period == null || period.equalsIgnoreCase(f.getProvenance().getFiscalPeriod())) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /**
     * Search the entire store for a fact whose value matches, within tolerance.
     *
     * <p>This is the workhorse behind hallucination detection: given a number the
     * model produced, ask "does <i>any</i> real filing value look like this?".
     * Deliberately generous across period and metric so that a correctly-recalled
     * number attached to the wrong label is still caught as <i>present</i> — the
     * guard distinguishes "not in any filing" (hallucination) from "in a filing
     * but mislabelled" (attribution error), which are different failure modes
     * with different analyst consequences.
     */
    public List<FinancialFact> findByValue(java.math.BigDecimal value, double relativeTolerance) {
        List<FinancialFact> hits = new ArrayList<>();
        if (value == null) return hits;
        double target = value.doubleValue();
        double tol = Math.abs(target) * relativeTolerance;
        // Absolute floor so small values are not matched with pathological slack.
        double absTol = Math.max(tol, Math.max(1e-6, Math.abs(target) * 1e-6));

        for (Map<String, List<FinancialFact>> byMetric : index.values()) {
            for (List<FinancialFact> series : byMetric.values()) {
                for (FinancialFact f : series) {
                    if (f.getValue() == null) continue;
                    double candidate = f.getValue().doubleValue();
                    if (Math.abs(candidate - target) <= Math.max(absTol, Math.abs(candidate) * relativeTolerance)) {
                        hits.add(f);
                    }
                }
            }
        }
        return hits;
    }

    /** Metrics available for a company, useful for capability disclosure. */
    public List<String> availableMetrics(String ticker) {
        Map<String, List<FinancialFact>> byMetric = index.get(ticker);
        if (byMetric == null) return new ArrayList<>();
        List<String> out = new ArrayList<>(byMetric.keySet());
        out.sort(String::compareTo);
        return out;
    }

    public boolean isLoaded(String ticker) {
        return Boolean.TRUE.equals(loadedCompanies.get(ticker));
    }

    public List<String> loadedTickers() {
        List<String> out = new ArrayList<>(index.keySet());
        out.sort(String::compareTo);
        return out;
    }

    public int totalFactCount() {
        int n = 0;
        for (Map<String, List<FinancialFact>> byMetric : index.values()) {
            for (List<FinancialFact> series : byMetric.values()) {
                n += series.size();
            }
        }
        return n;
    }

    /**
     * Distinct provenance objects across the store. Reported in every response
     * so the analyst knows the evidential base behind an answer.
     */
    public List<Provenance> distinctProvenances() {
        Map<String, Provenance> uniq = new LinkedHashMap<>();
        for (Map<String, List<FinancialFact>> byMetric : index.values()) {
            for (List<FinancialFact> series : byMetric.values()) {
                for (FinancialFact f : series) {
                    Provenance p = f.getProvenance();
                    if (p != null && p.getAccessionNumber() != null) {
                        uniq.putIfAbsent(p.getAccessionNumber()
                                + "|" + nvl(p.getXbrlTag())
                                + "|" + nvl(p.getPeriodEnd()), p);
                    }
                }
            }
        }
        return new ArrayList<>(uniq.values());
    }

    /** Distinct accession numbers, i.e. the filings backing everything held. */
    public List<String> distinctAccessions() {
        return distinctProvenances().stream()
                .map(Provenance::getAccessionNumber)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public void clear() {
        index.clear();
        loadedCompanies.clear();
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    /** Parse a period end date defensively; malformed dates sort last. */
    static LocalDate parseDateSafe(String iso) {
        try {
            return iso == null ? LocalDate.MAX : LocalDate.parse(iso);
        } catch (Exception e) {
            return LocalDate.MAX;
        }
    }
}
