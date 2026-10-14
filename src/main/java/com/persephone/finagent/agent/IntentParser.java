package com.persephone.finagent.agent;

import com.persephone.finagent.model.Metric;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an analyst's natural-language instruction into a structured task.
 *
 * <p>This is where the model would normally be asked to produce JSON. We use a
 * rule-based parser instead, for a reason that is central to the framework's
 * thesis: <b>intent parsing is the one place a hallucination is harmless.</b> If
 * the parser misreads an instruction, the worst outcome is the wrong report —
 * which the analyst sees immediately. If the parser hallucinated a <i>number</i>,
 * the outcome is an undetectable error in a finance decision. So this component
 * is allowed to be fallible, exactly because it cannot corrupt the evidential
 * chain. The guard layer downstream means the blast radius stops here.
 *
 * <p>A model-backed parser implementing the same {@link ParsedIntent} contract
 * can be dropped in without touching the rest of the harness, which is the
 * practical answer to "how do you use the LLM without trusting it?".
 */
public final class IntentParser {

    /**
     * Recognised intents.
     */
    public enum IntentType {
        /** Fetch statements, then answer a question about a metric. */
        QUERY_METRIC,
        /** Build a cross-quarter comparison table. */
        BUILD_COMPARISON,
        /** Compare one metric across peers. */
        COMPARE_PEERS,
        /** Refresh filings from EDGAR for a set of companies. */
        SYNC_FILINGS,
        /** Unrecognised; the harness asks for clarification. */
        UNKNOWN
    }

    /** A structured instruction, with every field the planner needs. */
    public static final class ParsedIntent {
        public IntentType type = IntentType.UNKNOWN;
        public final List<String> tickers = new ArrayList<>();
        public final List<Metric> metrics = new ArrayList<>();
        public String questionType = "LATEST";
        public String period;
        public String formFilter;
        public Integer periodCount;
        public String rawInstruction;
        /**
         * Set when the instruction explicitly names an annual period. Distinct
         * from a bare year: "FY2026" is annual, "FY2026 Q3" is a quarter, and
         * treating them alike would return a nine-month cumulative when a
         * three-month figure was asked for.
         */
        public boolean annualRequested;

        /** A short human-readable restatement, shown back for confirmation. */
        public String describe() {
            StringBuilder sb = new StringBuilder(type.name());
            if (!tickers.isEmpty()) sb.append(" tickers=").append(String.join(",", tickers));
            if (!metrics.isEmpty()) {
                List<String> ms = new ArrayList<>();
                for (Metric m : metrics) ms.add(m.name());
                sb.append(" metrics=").append(String.join(",", ms));
            }
            if (period != null) sb.append(" period=").append(period);
            sb.append(" question=").append(questionType);
            if (periodCount != null) sb.append(" periods=").append(periodCount);
            return sb.toString();
        }
    }

    /**
     * Parse an instruction.
     *
     * @param instruction the analyst's text
     * @param knownTickers tickers in the covered universe, used to spot company names
     */
    public ParsedIntent parse(String instruction, List<String> knownTickers) {
        ParsedIntent intent = new ParsedIntent();
        intent.rawInstruction = instruction;
        if (instruction == null || instruction.trim().isEmpty()) return intent;

        String lower = instruction.toLowerCase(Locale.ROOT);

        // --- companies ------------------------------------------------------
        if (knownTickers != null) {
            for (String t : knownTickers) {
                // Word-boundary match so "AMD" does not fire inside another token.
                if (Pattern.compile("\\b" + Pattern.quote(t.toLowerCase(Locale.ROOT)) + "\\b")
                        .matcher(lower).find()) {
                    intent.tickers.add(t.toUpperCase(Locale.ROOT));
                }
            }
        }

        // --- metrics --------------------------------------------------------
        for (Metric m : Metric.values()) {
            if (intent.metrics.contains(m)) continue;
            // Match the enum name and the human label, with synonyms the way
            // analysts actually type them.
            if (containsMetric(lower, m)) intent.metrics.add(m);
        }
        // Common synonyms that the generic matcher misses.
        addIfMentioned(lower, intent, Metric.REVENUE, "top line", "sales", "revenue");
        addIfMentioned(lower, intent, Metric.NET_INCOME, "bottom line", "profit", "earnings", "net income");
        addIfMentioned(lower, intent, Metric.OPERATING_CASH_FLOW, "cash flow from operations", "ocf");
        addIfMentioned(lower, intent, Metric.FREE_CASH_FLOW, "free cash flow", "fcf");
        addIfMentioned(lower, intent, Metric.GROSS_PROFIT, "gross margin", "gross profit");
        addIfMentioned(lower, intent, Metric.RND_EXPENSE, "r&d", "research and development");
        addIfMentioned(lower, intent, Metric.CAPEX, "capex", "capital expenditure", "capital expenditures");
        addIfMentioned(lower, intent, Metric.EPS_DILUTED, "eps", "earnings per share");

        // --- fiscal period --------------------------------------------------
        // "fiscal year 2026" / "fiscal 2026" / "FY2026" all denote the annual
        // period, which is a distinct fact from any quarterly label.
        Matcher fym = Pattern.compile(
                "\\bfiscal\\s*(?:year)?\\s*(\\d{4})\\b", Pattern.CASE_INSENSITIVE)
                .matcher(instruction);
        if (fym.find()) {
            intent.period = "FY" + fym.group(1);
            intent.annualRequested = true;
        }

        // Matches any four-digit year, not just 20xx. An out-of-range year such
        // as FY1985 must still be *parsed* and then refused downstream; if the
        // regex silently declined to match it, the query would fall back to
        // "latest available" and return a recent figure for a period the analyst
        // never asked about. Recognising the period is what makes the refusal
        // path reachable.
        if (intent.period == null) {
            Matcher pm = Pattern.compile(
                    "\\b(?:fy)?\\s?(\\d{4})\\s*(?:[-\\s]?\\s*(q[1-4]|fy))?\\b",
                    Pattern.CASE_INSENSITIVE).matcher(instruction);
            if (pm.find()) {
                String fy = pm.group(1);
                String q = pm.group(2);
                intent.period = (q == null || q.equalsIgnoreCase("fy"))
                        ? "FY" + fy
                        : "FY" + fy + " " + q.toUpperCase(Locale.ROOT);
                if (q != null && q.equalsIgnoreCase("fy")) intent.annualRequested = true;
            }
        }

        // Also recognise a bare quarter reference, e.g. "Q3 revenue", which
        // resolves against the most recent fiscal year the company has filed.
        if (intent.period == null) {
            Matcher qm = Pattern.compile("\\bq([1-4])\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(instruction);
            if (qm.find()) {
                intent.period = "Q" + qm.group(1);
            }
        }

        // --- period count ---------------------------------------------------
        Matcher cm = Pattern.compile("(?:last|trailing|past|最近|前)\\s*(\\d{1,2})\\s*(?:quarters?|qtrs?|periods?|个季度|期)",
                Pattern.CASE_INSENSITIVE).matcher(instruction);
        if (cm.find()) {
            intent.periodCount = Integer.parseInt(cm.group(1));
        }

        // --- form filter ----------------------------------------------------
        if (lower.contains("10-k") || lower.contains("annual report") || lower.contains("annual")) {
            intent.formFilter = "10-K";
        } else if (lower.contains("10-q") || lower.contains("quarterly")) {
            intent.formFilter = "10-Q";
        }

        // --- intent type (order matters: most specific first) ---------------
        boolean wantsTable = containsAny(lower, "table", "spreadsheet", "excel", "xlsx",
                "csv", "表格", "对比表", "导出");
        boolean wantsPeer = containsAny(lower, "compare", "versus", "vs ", "peer",
                "against each other", "横向", "对比", "比较") && intent.tickers.size() > 1;

        if (containsAny(lower, "sync", "refresh", "fetch", "download", "pull", "抓取", "拉取", "更新")
                && !wantsTable && !wantsPeer) {
            intent.type = IntentType.SYNC_FILINGS;
        } else if (wantsTable) {
            intent.type = IntentType.BUILD_COMPARISON;
        } else if (wantsPeer) {
            intent.type = IntentType.COMPARE_PEERS;
        } else if (containsAny(lower, "get", "show", "what", "how", "give", "tell",
                "value", "change", "growth", "查询", "多少", "怎么样", "增长")) {
            intent.type = IntentType.QUERY_METRIC;
        }

        // --- question type --------------------------------------------------
        if (containsAny(lower, "qoq", "quarter over quarter", "sequential", "环比")) {
            intent.questionType = "QOQ";
        } else if (containsAny(lower, "yoy", "year over year", "year-over-year", "同比")) {
            intent.questionType = "YOY";
        } else if (containsAny(lower, "trend", "history", "over time", "趋势", "历史")) {
            intent.questionType = "TREND";
        } else if (containsAny(lower, "margin", "利润率", "毛利率", "净利率")) {
            intent.questionType = "MARGIN";
        }

        // A peer comparison with no explicit metric should compare revenue, the
        // generic opener. Better to pick the conventional default than to fail.
        if (intent.type == IntentType.COMPARE_PEERS && intent.metrics.isEmpty()) {
            intent.metrics.add(Metric.REVENUE);
        }
        if (intent.type == IntentType.QUERY_METRIC && intent.metrics.isEmpty()
                && !intent.tickers.isEmpty()) {
            intent.metrics.add(Metric.REVENUE);
        }
        if (intent.periodCount == null && intent.type == IntentType.BUILD_COMPARISON) {
            intent.periodCount = 4;
        }

        return intent;
    }

    private static boolean containsMetric(String lower, Metric metric) {
        if (lower.contains(metric.name().toLowerCase(Locale.ROOT))) return true;
        String display = metric.displayName().toLowerCase(Locale.ROOT);
        if (lower.contains(display)) return true;
        // Tolerate the display name without punctuation: "stockholders equity".
        String loose = display.replace("'", "").replace("&", "and").replace("-", " ");
        return lower.contains(loose);
    }

    private static void addIfMentioned(String lower, ParsedIntent intent, Metric metric,
                                       String... phrases) {
        if (intent.metrics.contains(metric)) return;
        for (String p : phrases) {
            if (lower.contains(p)) {
                intent.metrics.add(metric);
                return;
            }
        }
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
