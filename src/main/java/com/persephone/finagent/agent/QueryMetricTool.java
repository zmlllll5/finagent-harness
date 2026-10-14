package com.persephone.finagent.agent;

import com.persephone.finagent.guard.NumericGuard;
import com.persephone.finagent.model.FinancialFact;
import com.persephone.finagent.model.Metric;
import com.persephone.finagent.model.VerifiedValue;
import com.persephone.finagent.provenance.FactStore;
import com.persephone.finagent.provenance.ProvenanceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool: answer a question about a specific metric with verified values.
 *
 * <p>Covers the analytical questions an analyst asks between "show me the
 * statements" and "export a model" — latest value, quarter-over-quarter change,
 * year-over-year change, trailing trend, margin.
 *
 * <p>The important structural point: the model may say <i>which</i> question to
 * ask and <i>which</i> metric it concerns, but the answer's numbers are read from
 * {@link FactStore} and re-verified through {@link NumericGuard}. Every growth
 * rate is recomputed here from two verified facts rather than taken from the
 * model, so an arithmetic slip in generation cannot become a reported figure.
 */
@Component
public class QueryMetricTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(QueryMetricTool.class);

    public static final String NAME = "queryMetric";

    private final FactStore store;
    private final ProvenanceTracker tracker;
    private final NumericGuard guard;

    public QueryMetricTool(FactStore store, ProvenanceTracker tracker, NumericGuard guard) {
        this.store = store;
        this.tracker = tracker;
        this.guard = guard;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "Answer a question about one metric for one company: latest value, "
                + "quarter-over-quarter change, year-over-year change, trend, or margin. "
                + "All numbers are read from the fact store and re-verified. "
                + "Args: ticker (String), metric (Metric), questionType "
                + "(LATEST|QOQ|YOY|TREND|MARGIN), formFilter (optional).";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> args) {
        String ticker = args.get("ticker") == null ? null : args.get("ticker").toString().toUpperCase();
        if (ticker == null) return ToolResult.fail("queryMetric requires 'ticker'");

        Metric metric;
        Object mArg = args.get("metric");
        if (mArg instanceof Metric) metric = (Metric) mArg;
        else metric = Metric.fromName(mArg == null ? null : mArg.toString()).orElse(null);
        if (metric == null) return ToolResult.fail("queryMetric requires a resolvable 'metric'");

        String questionType = args.get("questionType") == null
                ? "LATEST" : args.get("questionType").toString().toUpperCase();
        String formFilter = args.get("formFilter") == null
                ? null : args.get("formFilter").toString();
        String requestedPeriod = args.get("period") == null
                ? null : args.get("period").toString();
        boolean annualOnly = Boolean.TRUE.equals(args.get("annualOnly"));

        if (!store.isLoaded(ticker)) {
            return ToolResult.fail("No facts loaded for " + ticker + ". Run fetchStatements first.");
        }

        List<FinancialFact> series = store.series(ticker, metric, formFilter);
        if (series.isEmpty()) {
            return ToolResult.fail("No reported values for " + ticker + " " + metric
                    + (formFilter == null ? "" : " (form " + formFilter + ")"));
        }

        // Remove year-to-date cumulative facts from the working series unless one
        // was explicitly named. This is a single choke point rather than a guard
        // at every call site: "latest value", trailing trend, and
        // period-over-period change are all meaningless if a nine-month
        // cumulative can be picked up as if it were a quarter, and the failure is
        // silent because the figure itself is genuine.
        //
        // Note "(FY)" is NOT treated as cumulative here. It marks an annual
        // span, which for an annual-only series (balance-sheet items tagged once
        // a year) is the data, not a rolling-up of shorter periods. Only the
        // YTD markers, which exist solely to distinguish a cumulative from a
        // quarter of the same fiscal label, are filtered.
        if (requestedPeriod == null
                || !requestedPeriod.toUpperCase().contains("YTD")) {
            List<FinancialFact> nonCumulative = new ArrayList<>();
            for (FinancialFact f : series) {
                String label = f.getProvenance().getFiscalPeriod();
                if (label != null && label.toUpperCase().contains("YTD")) continue;
                nonCumulative.add(f);
            }
            if (!nonCumulative.isEmpty()) series = nonCumulative;
        }

        // --- period pinning -------------------------------------------------
        // When the instruction names a fiscal period, the answer MUST come from
        // that period or not at all. Falling back to "latest available" here
        // would be the single most dangerous failure this framework could have:
        // a correct-looking number silently substituted for the one that was
        // asked for, with no indication the requested period does not exist.
        // Refusing is the only acceptable outcome.
        if (requestedPeriod != null) {
            // Period matching runs in two passes, and the order is the whole
            // point. Duration qualifiers — "(FY)" for annual, "(9M YTD)" for a
            // nine-month cumulative — exist so a quarter and a cumulative for the
            // same fiscal label are distinguishable. So an EXACT match must be
            // tried first: otherwise "FY2026 Q3" would match the "FY2026 Q3
            // (9M YTD)" fact and silently return a nine-month figure where a
            // three-month one was asked for.
            //
            // Only if no exact label matches do we fall back to ignoring the
            // qualifier, which is what lets an analyst type "FY2026" and get the
            // annual figure rather than a refusal.
            String rawRequested = requestedPeriod;

            FinancialFact pinned = findExact(series, rawRequested);

            // An explicitly annual request ("fiscal year 2026") should match the
            // annual fact, whose stored label carries an "(FY)" span marker that
            // the analyst did not type.
            if (pinned == null && annualOnly) {
                for (FinancialFact f : series) {
                    String label = f.getProvenance().getFiscalPeriod();
                    if (label == null) continue;
                    String labelNorm = label.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
                    if (rawRequested.equalsIgnoreCase(labelNorm) && label.contains("(FY)")) {
                        pinned = f;
                        break;
                    }
                }
            }

            String normalised = rawRequested.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();

            // A bare quarter reference ("Q3") resolves against the most recent
            // fiscal year that has such a quarter, since no year was named.
            if (pinned == null && normalised.matches("(?i)^Q[1-4]$")) {
                for (int i = series.size() - 1; i >= 0; i--) {
                    String p = series.get(i).getProvenance().getFiscalPeriod();
                    if (p == null) continue;
                    if (p.toUpperCase().endsWith(" " + normalised.toUpperCase())
                            && !p.contains("YTD")) {
                        pinned = series.get(i);
                        break;
                    }
                }
            }

            // Second pass: tolerate the qualifier only when nothing matched
            // exactly. A candidate whose qualifier marks it as a cumulative is
            // rejected here, because returning a cumulative in answer to a
            // request for a single period is worse than refusing.
            if (pinned == null && !normalised.equals(rawRequested)) {
                for (FinancialFact f : series) {
                    String candidate = f.getProvenance().getFiscalPeriod();
                    if (candidate == null) continue;
                    String candidateNorm = candidate.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
                    if (normalised.equalsIgnoreCase(candidateNorm)
                            && !isCumulative(candidate)) {
                        pinned = f;
                        break;
                    }
                }
            }

            if (pinned == null) {
                for (FinancialFact f : series) {
                    String candidate = f.getProvenance().getFiscalPeriod();
                    if (candidate == null) continue;
                    String candidateNorm = candidate.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
                    if (normalised.equalsIgnoreCase(candidateNorm) && !isCumulative(candidate)) {
                        pinned = f;
                        break;
                    }
                }
            }
            if (pinned == null) {
                List<String> available = new ArrayList<>();
                for (int i = series.size() - 1; i >= 0 && available.size() < 8; i--) {
                    String p = series.get(i).getProvenance().getFiscalPeriod();
                    if (!available.contains(p)) available.add(p);
                }
                log.warn("REFUSED: {} {} requested for period {} which does not exist",
                        ticker, metric, requestedPeriod);
                return ToolResult.fail(String.format(
                        "REFUSED: no %s %s value for period '%s'. "
                        + "This period is not present in any filing retrieved for %s. "
                        + "Reported periods available (most recent first): %s. "
                        + "The harness will not substitute a different period's figure.",
                        ticker, metric, requestedPeriod, ticker,
                        String.join(", ", available)));
            }
            series = new ArrayList<>();
            series.add(pinned);
            questionType = "LATEST";
        }

        ProvenanceTracker.CitationSet citations = tracker.newCitationSet();
        NumericGuard.GuardStats stats = guard.newStats();

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("ticker", ticker);
        answer.put("metric", metric.name());
        answer.put("questionType", questionType);
        answer.put("periodsAvailable", series.size());
        if (requestedPeriod != null) {
            answer.put("requestedPeriod", requestedPeriod);
            answer.put("periodPinEnforced", true);
        }

        switch (questionType) {
            case "QOQ":   answer.putAll(changeAnalysis(series, 1, "QoQ", citations, stats)); break;
            case "YOY":   answer.putAll(changeAnalysis(series, 4, "YoY", citations, stats)); break;
            case "TREND": answer.putAll(trendAnalysis(series, citations, stats)); break;
            case "MARGIN": answer.putAll(marginAnalysis(ticker, series, citations, stats)); break;
            case "LATEST":
            default:      answer.putAll(latestValue(series, citations, stats)); break;
        }

        List<String> footnotes = new ArrayList<>();
        List<com.persephone.finagent.model.Provenance> cs = citations.citations();
        for (int i = 0; i < cs.size(); i++) {
            footnotes.add(tracker.renderFootnote(i + 1, cs.get(i), ticker));
        }
        answer.put("footnotes", footnotes);
        answer.put("verificationStats", stats.summary());

        String summary = String.format("%s %s %s — %s", ticker, metric, questionType, stats.summary());
        log.info(summary);
        return ToolResult.ok(summary, answer);
    }

    /** Exact label match. Must be tried before any fuzzy match. */
    private static FinancialFact findExact(List<FinancialFact> series, String period) {
        for (FinancialFact f : series) {
            if (period.equalsIgnoreCase(f.getProvenance().getFiscalPeriod())) return f;
        }
        return null;
    }

    /**
     * Whether a period label marks a year-to-date cumulative.
     *
     * <p>Cumulatives must never be substituted for a single-period request: a
     * nine-month figure presented as a quarter would overstate the metric by
     * roughly 3x, and the error survives review because the number is real and
     * correctly sourced — just for the wrong span.
     *
     * <p>"(FY)" is deliberately excluded. It marks an annual span, and for
     * annually-tagged balance-sheet items that span <i>is</i> the period; only
     * the YTD markers, which exist to distinguish a cumulative from a quarter of
     * the same fiscal label, indicate a rolling-up.
     */
    private static boolean isCumulative(String periodLabel) {
        if (periodLabel == null) return false;
        return periodLabel.toUpperCase().contains("YTD");
    }

    private Map<String, Object> latestValue(List<FinancialFact> series,
                                            ProvenanceTracker.CitationSet citations,
                                            NumericGuard.GuardStats stats) {
        FinancialFact latest = series.get(series.size() - 1);
        // Route through the guard even though the fact is already trusted: the
        // point is that this value took the same path as a model-proposed one,
        // so the guard's accounting stays complete for the evaluation report.
        NumericGuard.VerificationResult r = guard.verify(
                latest.getValue(), latest.getTicker(),
                Metric.valueOf(latest.getMetric()),
                latest.getProvenance().getFiscalPeriod(), latest.getMetric());
        guard.record(stats, r);
        int ref = citations.register(latest);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", latest.getProvenance().getFiscalPeriod());
        out.put("value", latest.getValue().toPlainString());
        out.put("display", BuildComparisonTool.formatScaled(latest.getValue(), latest.getUnit()));
        out.put("unit", latest.getUnit());
        out.put("footnote", ref);
        out.put("asOf", latest.getProvenance().getPeriodEnd());
        out.put("formType", latest.getProvenance().getFormType());
        out.put("verified", r.outcome == NumericGuard.VisionOutcome.MATCHED);
        return out;
    }

    /**
     * Sequential change between now and {@code lags} periods back.
     *
     * <p>Recomputed from the two facts rather than accepted from a model. When
     * there are not enough periods the answer says so explicitly — an analyst
     * would rather hear "insufficient history" than a plausible-looking number
     * derived from a mismatched pair.
     */
    private Map<String, Object> changeAnalysis(List<FinancialFact> series, int lags, String label,
                                               ProvenanceTracker.CitationSet citations,
                                               NumericGuard.GuardStats stats) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (series.size() <= lags) {
            out.put("error", "insufficient history for " + label
                    + ": need " + (lags + 1) + " periods, have " + series.size());
            return out;
        }
        FinancialFact current = series.get(series.size() - 1);
        FinancialFact prior = series.get(series.size() - 1 - lags);

        BigDecimal change = current.getValue().subtract(prior.getValue());
        BigDecimal pct = prior.getValue().doubleValue() == 0
                ? null
                : change.divide(prior.getValue(), 6, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);

        int refCur = citations.register(current);
        int refPrior = citations.register(prior);

        // Record both inputs against the guard so a stated change is only ever
        // as trustworthy as the two facts behind it.
        guard.record(stats, guard.verify(current.getValue(), current.getTicker(),
                Metric.valueOf(current.getMetric()),
                current.getProvenance().getFiscalPeriod(), current.getMetric()));
        guard.record(stats, guard.verify(prior.getValue(), prior.getTicker(),
                Metric.valueOf(prior.getMetric()),
                prior.getProvenance().getFiscalPeriod(), prior.getMetric()));

        out.put("currentPeriod", current.getProvenance().getFiscalPeriod());
        out.put("currentValue", current.getValue().toPlainString());
        out.put("currentDisplay", BuildComparisonTool.formatScaled(current.getValue(), current.getUnit()));
        out.put("priorPeriod", prior.getProvenance().getFiscalPeriod());
        out.put("priorValue", prior.getValue().toPlainString());
        out.put("priorDisplay", BuildComparisonTool.formatScaled(prior.getValue(), prior.getUnit()));
        out.put(label + "Change", change.toPlainString());
        out.put(label + "ChangeDisplay", BuildComparisonTool.formatScaled(change, current.getUnit()));
        out.put(label + "ChangePct", pct == null ? "n/a" : pct.toPlainString() + "%");
        out.put("formula", "(current - prior) / prior, recomputed from verified facts");
        out.put("footnotesForThisAnswer", new int[]{refCur, refPrior});
        return out;
    }

    private Map<String, Object> trendAnalysis(List<FinancialFact> series,
                                              ProvenanceTracker.CitationSet citations,
                                              NumericGuard.GuardStats stats) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> points = new ArrayList<>();
        Integer prevRef = null;
        for (FinancialFact f : series) {
            int ref = citations.register(f);
            guard.record(stats, guard.verify(f.getValue(), f.getTicker(),
                    Metric.valueOf(f.getMetric()), f.getProvenance().getFiscalPeriod(), f.getMetric()));
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("period", f.getProvenance().getFiscalPeriod());
            pt.put("value", f.getValue().toPlainString());
            pt.put("display", BuildComparisonTool.formatScaled(f.getValue(), f.getUnit()));
            pt.put("footnote", ref);
            if (prevRef != null) pt.put("prevFootnote", prevRef);
            points.add(pt);
            prevRef = ref;
        }
        out.put("series", points);
        out.put("direction", direction(series));
        return out;
    }

    private String direction(List<FinancialFact> series) {
        if (series.size() < 2) return "insufficient history";
        BigDecimal first = series.get(0).getValue();
        BigDecimal last = series.get(series.size() - 1).getValue();
        int cmp = last.compareTo(first);
        if (cmp > 0) return "increasing";
        if (cmp < 0) return "decreasing";
        return "flat";
    }

    /**
     * Margin analysis: computed from revenue plus the relevant profit line, with
     * both inputs cited so the ratio is reproducible.
     */
    private Map<String, Object> marginAnalysis(String ticker, List<FinancialFact> series,
                                               ProvenanceTracker.CitationSet citations,
                                               NumericGuard.GuardStats stats) {
        FinancialFact profit = series.get(series.size() - 1);
        String period = profit.getProvenance().getFiscalPeriod();
        Metric profitMetric = Metric.valueOf(profit.getMetric());

        Metric denominatorMetric = (profitMetric == Metric.GROSS_PROFIT
                || profitMetric == Metric.OPERATING_INCOME
                || profitMetric == Metric.NET_INCOME)
                ? Metric.REVENUE : Metric.REVENUE;

        FinancialFact revenue = null;
        for (FinancialFact f : store.series(ticker, denominatorMetric)) {
            if (period.equalsIgnoreCase(f.getProvenance().getFiscalPeriod())) {
                revenue = f;
                break;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        if (revenue == null || revenue.getValue().doubleValue() == 0) {
            out.put("error", "cannot compute margin for " + period
                    + ": revenue for that period is not available");
            return out;
        }

        BigDecimal ratio = profit.getValue().divide(revenue.getValue(), 6, RoundingMode.HALF_UP);
        BigDecimal pct = ratio.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);

        int refNum = citations.register(profit);
        int refDen = citations.register(revenue);
        guard.record(stats, guard.verify(profit.getValue(), ticker, profitMetric, period, "numerator"));
        guard.record(stats, guard.verify(revenue.getValue(), ticker, Metric.REVENUE, period, "denominator"));

        // Cross-reference the margin against the component route when possible:
        // gross profit should equal revenue - cost of revenue.
        if (profitMetric == Metric.GROSS_PROFIT) {
            FinancialFact cogs = null;
            for (FinancialFact f : store.series(ticker, Metric.COST_OF_REVENUE)) {
                if (period.equalsIgnoreCase(f.getProvenance().getFiscalPeriod())) { cogs = f; break; }
            }
            if (cogs != null) {
                BigDecimal implied = revenue.getValue().subtract(cogs.getValue());
                out.put("crossReference", guard.crossReference(ticker, Metric.GROSS_PROFIT, period,
                        profit.getValue(), "reported gross profit",
                        implied, "revenue - cost of revenue"));
            }
        }

        out.put("period", period);
        out.put("numeratorMetric", profitMetric.name());
        out.put("numeratorFootnote", refNum);
        out.put("denominatorMetric", denominatorMetric.name());
        out.put("denominatorFootnote", refDen);
        out.put("marginPct", pct.toPlainString() + "%");
        out.put("formula", profitMetric.name() + " / " + denominatorMetric.name());
        return out;
    }
}
