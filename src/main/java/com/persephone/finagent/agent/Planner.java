package com.persephone.finagent.agent;

import com.persephone.finagent.model.Metric;
import com.persephone.finagent.profile.UserProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a parsed intent into an ordered tool plan.
 *
 * <p>The planner is where user habits enter the pipeline, and the boundary it
 * respects is worth stating precisely: <b>the profile influences plan shape,
 * never plan truth.</b> Concretely, if the analyst habitually leads with
 * operating margin, the planner puts operating-income metrics earlier in the
 * fetch list and the table rows. It does not decide which numbers are correct,
 * does not pre-fill values, and cannot cause a fact to be loaded that is not in
 * a filing.
 *
 * <p>A plan is a plain list of {@link Step}s, which makes it inspectable — the
 * harness surfaces the plan alongside the answer so an analyst can see what the
 * agent decided to do before trusting the output.
 */
@Component
public class Planner {

    /** One tool invocation with its resolved arguments. */
    public static final class Step {
        public final String tool;
        public final Map<String, Object> args;
        public final String rationale;

        public Step(String tool, Map<String, Object> args, String rationale) {
            this.tool = tool;
            this.args = args;
            this.rationale = rationale;
        }

        @Override
        public String toString() {
            return tool + args + " // " + rationale;
        }
    }

    /** A complete plan. */
    public static final class Plan {
        public final List<Step> steps = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public IntentParser.ParsedIntent intent;

        public Plan add(String tool, Map<String, Object> args, String rationale) {
            steps.add(new Step(tool, args, rationale));
            return this;
        }

        public Plan note(String note) {
            notes.add(note);
            return this;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("intent: ").append(intent == null ? "?" : intent.describe());
            for (int i = 0; i < steps.size(); i++) {
                sb.append("\n  ").append(i + 1).append(". ").append(steps.get(i).tool);
                if (!steps.get(i).args.isEmpty()) sb.append(' ').append(steps.get(i).args);
            }
            if (!notes.isEmpty()) {
                sb.append("\n  notes: ").append(String.join("; ", notes));
            }
            return sb.toString();
        }
    }

    /**
     * Build a plan.
     *
     * @param intent     parsed instruction
     * @param profile    the requesting analyst's learned habits, may be null
     * @param resolver   maps ticker -> CompanyRef; supplied by the harness
     */
    public Plan plan(IntentParser.ParsedIntent intent, UserProfile profile,
                     java.util.function.Function<String, Object> resolver) {
        Plan plan = new Plan();
        plan.intent = intent;

        // --- habit adaptation: fill in what the analyst usually means ---------
        List<Metric> metrics = new ArrayList<>(intent.metrics);
        if (profile != null) {
            if (metrics.isEmpty() && intent.type == IntentParser.IntentType.QUERY_METRIC) {
                List<String> habitual = profile.rankedMetrics(3);
                for (String m : habitual) {
                    Metric.fromName(m).ifPresent(metrics::add);
                }
                if (!metrics.isEmpty()) {
                    plan.note("no metric named; using habitual selection from profile: "
                            + metrics.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(",")));
                }
            }
            if (intent.periodCount == null
                    && intent.type == IntentParser.IntentType.BUILD_COMPARISON) {
                intent.periodCount = profile.getPreferredPeriodCount();
                plan.note("period window defaulted to profile preference: " + intent.periodCount);
            }
            if (intent.formFilter == null && profile.getPreferredForm() != null) {
                // Only apply the habitual form filter to comparison tables, where
                // mixing annual and quarterly figures is the real hazard.
                if (intent.type == IntentParser.IntentType.BUILD_COMPARISON) {
                    plan.note("form filter defaulted to profile preference: " + profile.getPreferredForm());
                }
            }
        }

        // --- step 1: always ensure the facts are loaded ----------------------
        // Provenance can only exist if the filing was actually read, so every
        // plan begins with a fetch for each company in scope.
        for (String ticker : intent.tickers) {
            Object company = resolver.apply(ticker);
            if (company == null) {
                plan.note("ticker not in covered universe: " + ticker);
                continue;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("company", company);
            if (!metrics.isEmpty()) args.put("metrics", metrics);
            if (profile != null) args.put("userId", profile.getUserId());
            plan.add(FetchStatementsTool.NAME, args,
                    "load filing facts with provenance before any analysis");
        }

        if (intent.tickers.isEmpty() && intent.type != IntentParser.IntentType.UNKNOWN) {
            plan.note("no company identified in the instruction; specify a ticker or CIK");
        }

        // --- step 2: the analysis the analyst asked for ----------------------
        switch (intent.type) {
            case BUILD_COMPARISON: {
                for (String ticker : intent.tickers) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("ticker", ticker);
                    if (!metrics.isEmpty()) args.put("metrics", metrics);
                    args.put("periodCount", intent.periodCount == null ? 4 : intent.periodCount);
                    if (intent.formFilter != null) args.put("formFilter", intent.formFilter);
                    plan.add(BuildComparisonTool.NAME, args,
                            "cross-quarter table with per-cell provenance");
                }
                break;
            }
            case COMPARE_PEERS: {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("tickers", intent.tickers);
                args.put("metric", metrics.isEmpty() ? Metric.REVENUE : metrics.get(0));
                if (intent.period != null) args.put("period", intent.period);
                args.put("deriveMargins", true);
                plan.add(PeerComparisonTool.NAME, args,
                        "one metric across peers, each company separately cited");
                break;
            }
            case QUERY_METRIC: {
                for (String ticker : intent.tickers) {
                    for (Metric m : metrics) {
                        Map<String, Object> args = new LinkedHashMap<>();
                        args.put("ticker", ticker);
                        args.put("metric", m);
                        args.put("questionType", intent.questionType);
                        // Pass the requested period through explicitly. This is
                        // what lets the query tool enforce "answer only for this
                        // period, or refuse" instead of quietly returning the
                        // latest available figure.
                        if (intent.period != null) args.put("period", intent.period);
                        if (intent.annualRequested) args.put("annualOnly", Boolean.TRUE);
                        if (intent.formFilter != null) args.put("formFilter", intent.formFilter);
                        plan.add(QueryMetricTool.NAME, args,
                                "answer with verified, cited values"
                                + (intent.period == null ? "" : "; period pinned to " + intent.period));
                    }
                }
                break;
            }
            case SYNC_FILINGS:
                plan.note("filings refreshed; run a comparison or query to analyse them");
                break;
            case UNKNOWN:
            default:
                plan.note("instruction not understood; supported: fetch statements, "
                        + "build a cross-quarter table, compare peers, query a metric");
                break;
        }

        return plan;
    }
}
