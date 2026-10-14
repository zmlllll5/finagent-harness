package com.persephone.finagent.agent;

import com.persephone.finagent.model.FinancialFact;
import com.persephone.finagent.model.Metric;
import com.persephone.finagent.model.Provenance;
import com.persephone.finagent.provenance.FactStore;
import com.persephone.finagent.provenance.ProvenanceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool: compare one metric across several companies for one period.
 *
 * <p>Answers the question every sector analyst actually opens with — "how does
 * NVDA's gross margin compare to AMD and INTC this quarter?" — and answers it in
 * a form that can be checked: one row per company, each with its own filing
 * citation, so a figure that looks anomalous can be traced in one click.
 *
 * <p>Companies with no reported value for the metric are listed explicitly as
 * not-reported rather than omitted. Silently dropping a peer from a comparison
 * is the kind of quiet omission that changes a conclusion.
 */
@Component
public class PeerComparisonTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PeerComparisonTool.class);

    public static final String NAME = "comparePeers";

    private final FactStore store;
    private final ProvenanceTracker tracker;

    public PeerComparisonTool(FactStore store, ProvenanceTracker tracker) {
        this.store = store;
        this.tracker = tracker;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "Compare a single metric across multiple companies for a given fiscal period. "
                + "Returns per-company values each with its own filing citation, plus "
                + "explicit entries for companies that did not report the metric. "
                + "Args: tickers (list of String), metric (Metric), period (String), "
                + "deriveMargins (boolean, optional).";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> args) {
        List<String> tickers = new ArrayList<>();
        Object tArg = args.get("tickers");
        if (tArg instanceof List) {
            for (Object o : (List<Object>) tArg) {
                if (o != null) tickers.add(o.toString().toUpperCase());
            }
        }
        if (tickers.isEmpty()) {
            return ToolResult.fail("comparePeers requires a non-empty 'tickers' list");
        }

        Metric metric;
        Object mArg = args.get("metric");
        if (mArg instanceof Metric) metric = (Metric) mArg;
        else metric = Metric.fromName(mArg == null ? null : mArg.toString()).orElse(null);
        if (metric == null) {
            return ToolResult.fail("comparePeers requires a resolvable 'metric'");
        }

        String period = args.get("period") == null ? null : args.get("period").toString();
        boolean deriveMargins = Boolean.TRUE.equals(args.get("deriveMargins"));

        ProvenanceTracker.CitationSet citations = tracker.newCitationSet();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Map<Provenance, String> tickerByProv = new LinkedHashMap<>();

        for (String ticker : tickers) {
            if (!store.isLoaded(ticker)) {
                missing.add(ticker + " (no facts loaded)");
                continue;
            }
            FinancialFact fact = resolveFact(ticker, metric, period);
            if (fact == null) {
                missing.add(ticker + " (metric not reported"
                        + (period == null ? "" : " for " + period) + ")");
                continue;
            }
            int ref = citations.register(fact);
            tickerByProv.put(fact.getProvenance(), ticker);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticker", ticker);
            row.put("metric", metric.name());
            row.put("period", fact.getProvenance().getFiscalPeriod());
            row.put("value", fact.getValue().toPlainString());
            row.put("display", BuildComparisonTool.formatScaled(fact.getValue(), fact.getUnit()));
            row.put("footnote", ref);
            row.put("provenance", fact.getProvenance());

            if (deriveMargins) {
                Map<String, Object> derived = computeMargins(ticker, fact.getProvenance().getFiscalPeriod(), citations);
                row.putAll(derived);
            }
            rows.add(row);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("metric", metric.name());
        payload.put("period", period == null ? "latest available per company" : period);
        payload.put("rows", rows);
        payload.put("notReported", missing);

        List<String> footnotes = new ArrayList<>();
        List<Provenance> citationList = citations.citations();
        for (int i = 0; i < citationList.size(); i++) {
            footnotes.add(tracker.renderFootnote(i + 1, citationList.get(i),
                    tickerByProv.get(citationList.get(i))));
        }
        payload.put("footnotes", footnotes);

        String summary = String.format("Peer comparison of %s across %d companies, %d resolved, %d not reported",
                metric, tickers.size(), rows.size(), missing.size());
        log.info(summary);
        return ToolResult.ok(summary, payload);
    }

    /**
     * Find the fact for a metric/period, or the latest available when no period
     * was specified. Choosing "latest" rather than "any" matters: a comparison
     * across peers using different, arbitrary periods would be misleading.
     */
    private FinancialFact resolveFact(String ticker, Metric metric, String period) {
        List<FinancialFact> series = store.series(ticker, metric);
        if (series.isEmpty()) return null;
        if (period == null) return series.get(series.size() - 1);
        for (FinancialFact f : series) {
            if (period.equalsIgnoreCase(f.getProvenance().getFiscalPeriod())) return f;
        }
        return null;
    }

    /**
     * Compute margin ratios from already-verified facts. Each derived ratio is
     * backed by the two facts that produced it, both registered as citations.
     */
    private Map<String, Object> computeMargins(String ticker, String period,
                                              ProvenanceTracker.CitationSet citations) {
        Map<String, Object> out = new LinkedHashMap<>();
        FinancialFact revenue = findExact(ticker, Metric.REVENUE, period);
        if (revenue == null || revenue.getValue().doubleValue() == 0) return out;

        FinancialFact gross = findExact(ticker, Metric.GROSS_PROFIT, period);
        if (gross == null) {
            FinancialFact cogs = findExact(ticker, Metric.COST_OF_REVENUE, period);
            if (cogs != null) {
                java.math.BigDecimal gp = revenue.getValue().subtract(cogs.getValue());
                int r1 = citations.register(revenue);
                int r2 = citations.register(cogs);
                out.put("grossMarginPct", pct(gp, revenue.getValue()));
                out.put("grossMarginFormula", "(Revenue - CostOfRevenue) / Revenue");
                out.put("grossMarginRefs", new int[]{r1, r2});
            }
        } else {
            int r1 = citations.register(gross);
            int r2 = citations.register(revenue);
            out.put("grossMarginPct", pct(gross.getValue(), revenue.getValue()));
            out.put("grossMarginFormula", "GrossProfit / Revenue");
            out.put("grossMarginRefs", new int[]{r1, r2});
        }

        FinancialFact ni = findExact(ticker, Metric.NET_INCOME, period);
        if (ni != null) {
            int r1 = citations.register(ni);
            int r2 = citations.register(revenue);
            out.put("netMarginPct", pct(ni.getValue(), revenue.getValue()));
            out.put("netMarginFormula", "NetIncome / Revenue");
            out.put("netMarginRefs", new int[]{r1, r2});
        }
        return out;
    }

    private FinancialFact findExact(String ticker, Metric metric, String period) {
        for (FinancialFact f : store.series(ticker, metric)) {
            if (period.equalsIgnoreCase(f.getProvenance().getFiscalPeriod())) return f;
        }
        return null;
    }

    private static String pct(java.math.BigDecimal numerator, java.math.BigDecimal denominator) {
        if (denominator == null || denominator.doubleValue() == 0) return "n/a";
        java.math.BigDecimal ratio = numerator.divide(denominator, 6, java.math.RoundingMode.HALF_UP);
        return ratio.multiply(new java.math.BigDecimal("100"))
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
