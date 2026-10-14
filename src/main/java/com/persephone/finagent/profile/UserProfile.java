package com.persephone.finagent.profile;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A learned model of one analyst's habits.
 *
 * <p>The brief asks how the agent adapts to a user over time. The design
 * principle here: <b>adapt the defaults, never the facts.</b> Personalisation
 * may change what the agent shows first, how it formats numbers, and which
 * periods it reaches for — it must never change what a filing says. A system
 * that "learns" to report a number a user seems to like is worse than useless
 * in a research context.
 *
 * <p>So this profile influences only presentation and planning:
 * <ul>
 *   <li><b>Metric affinity.</b> Counts of how often each metric is requested.
 *       Drives default row ordering and which metrics appear when an analyst
 *       asks for "the usual" without naming them.</li>
 *   <li><b>Period window.</b> Analysts settle into a horizon — trailing four
 *       quarters, trailing eight. Learned from what they actually request.</li>
 *   <li><b>Unit scale.</b> Some desks work in millions, some in billions. Getting
 *       this wrong on every table is a daily irritant.</li>
 *   <li><b>Ticker watchlist.</b> Which companies they actually follow, so
 *       "compare the sector" means <i>their</i> sector.</li>
 *   <li><b>Form preference.</b> Quarterly vs. annual emphasis.</li>
 * </ul>
 *
 * <p>All counters are simple observations with no decay for now; the extension
 * point for recency-weighting is {@link #observeMetric} and is noted there.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserProfile {

    private String userId;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    /** metric name -> number of times requested. */
    private Map<String, Integer> metricAffinity = new LinkedHashMap<>();

    /** ticker -> number of times referenced. */
    private Map<String, Integer> tickerAffinity = new LinkedHashMap<>();

    /** Preferred number of periods in a comparison table. */
    private int preferredPeriodCount = 4;

    /** "M" (millions) or "B" (billions). */
    private String preferredUnitScale = "M";

    /** Preferred currency; USD for SEC filings. */
    private String preferredCurrency = "USD";

    /** "10-Q" or "10-K": which form the analyst tends to work from. */
    private String preferredForm = "10-Q";

    /** Ordered metric list the analyst most recently asked for. */
    private java.util.List<String> lastMetricSelection = new java.util.ArrayList<>();

    /** Number of interactions recorded against this profile. */
    private int interactions = 0;

    public UserProfile() { }

    public UserProfile(String userId) { this.userId = userId; }

    /**
     * Record that a metric was requested.
     *
     * <p>Extension point: a production system should decay counts by recency so
     * that a metric abandoned six months ago stops driving defaults. The hook is
     * deliberately here rather than spread through call sites.
     */
    public void observeMetric(String metric) {
        if (metric == null) return;
        metricAffinity.merge(metric, 1, Integer::sum);
        touch();
    }

    public void observeMetrics(java.util.Collection<String> metrics) {
        if (metrics == null) return;
        for (String m : metrics) observeMetric(m);
        this.lastMetricSelection = new java.util.ArrayList<>(metrics);
        touch();
    }

    public void observeTicker(String ticker) {
        if (ticker == null) return;
        tickerAffinity.merge(ticker.toUpperCase(), 1, Integer::sum);
        touch();
    }

    public void observePeriodCount(int count) {
        if (count > 0) {
            this.preferredPeriodCount = count;
            touch();
        }
    }

    public void observeUnitScale(String scale) {
        if (scale != null && (scale.equals("M") || scale.equals("B"))) {
            this.preferredUnitScale = scale;
            touch();
        }
    }

    public void observeForm(String form) {
        if (form != null && (form.equalsIgnoreCase("10-Q") || form.equalsIgnoreCase("10-K"))) {
            this.preferredForm = form.toUpperCase();
            touch();
        }
    }

    public void incrementInteractions() {
        this.interactions++;
        touch();
    }

    /**
     * Metrics the analyst requests most, most-frequent first. Used to order
     * table rows so the numbers they care about are at the top.
     */
    public java.util.List<String> rankedMetrics(int limit) {
        java.util.List<String> ranked = new java.util.ArrayList<>(metricAffinity.keySet());
        ranked.sort((a, b) -> Integer.compare(
                metricAffinity.getOrDefault(b, 0), metricAffinity.getOrDefault(a, 0)));
        return ranked.size() <= limit ? ranked : ranked.subList(0, limit);
    }

    /** Tickers the analyst follows, most-referenced first. */
    public java.util.List<String> rankedTickers(int limit) {
        java.util.List<String> ranked = new java.util.ArrayList<>(tickerAffinity.keySet());
        ranked.sort((a, b) -> Integer.compare(
                tickerAffinity.getOrDefault(b, 0), tickerAffinity.getOrDefault(a, 0)));
        return ranked.size() <= limit ? ranked : ranked.subList(0, limit);
    }

    private void touch() { this.updatedAt = Instant.now(); }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Map<String, Integer> getMetricAffinity() { return metricAffinity; }
    public void setMetricAffinity(Map<String, Integer> v) { this.metricAffinity = v; }
    public Map<String, Integer> getTickerAffinity() { return tickerAffinity; }
    public void setTickerAffinity(Map<String, Integer> v) { this.tickerAffinity = v; }
    public int getPreferredPeriodCount() { return preferredPeriodCount; }
    public void setPreferredPeriodCount(int v) { this.preferredPeriodCount = v; }
    public String getPreferredUnitScale() { return preferredUnitScale; }
    public void setPreferredUnitScale(String v) { this.preferredUnitScale = v; }
    public String getPreferredCurrency() { return preferredCurrency; }
    public void setPreferredCurrency(String v) { this.preferredCurrency = v; }
    public String getPreferredForm() { return preferredForm; }
    public void setPreferredForm(String v) { this.preferredForm = v; }
    public java.util.List<String> getLastMetricSelection() { return lastMetricSelection; }
    public void setLastMetricSelection(java.util.List<String> v) { this.lastMetricSelection = v; }
    public int getInteractions() { return interactions; }
    public void setInteractions(int v) { this.interactions = v; }
}
