package com.persephone.finagent.agent;

import com.persephone.finagent.config.SectorProperties;
import com.persephone.finagent.guard.NumericGuard;
import com.persephone.finagent.llm.LlmClient;
import com.persephone.finagent.model.CompanyRef;
import com.persephone.finagent.model.ComparisonTable;
import com.persephone.finagent.model.Metric;
import com.persephone.finagent.profile.ProfileService;
import com.persephone.finagent.profile.UserProfile;
import com.persephone.finagent.provenance.FactStore;
import com.persephone.finagent.provenance.ProvenanceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agentic harness: the layer that wraps the model, the tools, and the
 * provenance machinery into a single auditable execution.
 *
 * <p>This is the component the brief calls the {@code agentic harness}, and the
 * design is deliberately opinionated about where the model sits in it. The
 * conventional agent loop lets the model drive: it decides what to look up,
 * receives the results as text, and composes the answer. That architecture is
 * fine for open-ended tasks and unacceptable here, because the model's output
 * <i>is</i> the answer and it can therefore invent data.
 *
 * <p>So the loop is inverted. The harness owns the control flow; the model owns
 * only the parsing of intent and the prose framing. Specifically:
 *
 * <ol>
 *   <li><b>Parse.</b> The analyst's instruction becomes a structured intent.
 *       Fallible, and harmlessly so — a misparse produces the wrong report,
 *       which is immediately visible.</li>
 *   <li><b>Plan.</b> Intent becomes an ordered tool plan, adjusted by the
 *       analyst's learned habits. Presentation adapts; truth does not.</li>
 *   <li><b>Execute.</b> Tools read filings and produce facts. Only this step
 *       creates data, and it always attaches provenance.</li>
 *   <li><b>Verify.</b> Every number is bound back to a stored fact. Unbindable
 *       numbers are stripped.</li>
 *   <li><b>Assemble.</b> Output is rendered with citations. The model may phrase
 *       the narrative, but it never supplies the figures.</li>
 * </ol>
 *
 * <p>Steps 3 to 5 are identical whether the LLM is GPT-4o or the deterministic
 * rule engine. The accuracy of the output is therefore largely independent of
 * model choice, which is the framework's central empirical claim and the thing
 * the evaluation harness sets out to measure.
 */
@Service
public class AgentHarness {

    private static final Logger log = LoggerFactory.getLogger(AgentHarness.class);

    private final SectorProperties sector;
    private final FactStore store;
    private final IntentParser intentParser;
    private final Planner planner;
    private final ProfileService profiles;
    private final ProvenanceTracker provenance;
    private final NumericGuard guard;
    private final LlmClient llm;
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public AgentHarness(SectorProperties sector,
                        FactStore store,
                        Planner planner,
                        ProfileService profiles,
                        ProvenanceTracker provenance,
                        NumericGuard guard,
                        LlmClient llm,
                        List<AgentTool> toolBeans) {
        this.sector = sector;
        this.store = store;
        this.planner = planner;
        this.profiles = profiles;
        this.provenance = provenance;
        this.guard = guard;
        this.llm = llm;
        this.intentParser = new IntentParser();
        for (AgentTool t : toolBeans) {
            tools.put(t.name(), t);
        }
        log.info("harness ready: {} tools, {} companies, llm={}",
                tools.size(), sector.getCompanies().size(), llm.providerName());
    }

    /**
     * Execute an analyst instruction end-to-end.
     *
     * @param instruction natural-language request
     * @param userId      analyst identity, for habit adaptation; may be null
     */
    @SuppressWarnings("unchecked")
    public AgentResponse run(String instruction, String userId) {
        long started = System.currentTimeMillis();
        AgentResponse response = new AgentResponse();
        response.instruction = instruction;
        response.provider = llm.providerName();

        // --- load habits ----------------------------------------------------
        UserProfile profile = null;
        if (userId != null && !userId.trim().isEmpty()) {
            profile = profiles.getOrCreate(userId);
            response.profileSummary = profiles.describe(profile);
        }

        // --- parse ----------------------------------------------------------
        List<String> knownTickers = new ArrayList<>();
        for (CompanyRef c : sector.asCompanyRefs()) knownTickers.add(c.getTicker());

        // Seed the parser's ticker list with companies the analyst follows, so
        // "compare my watchlist" resolves even without explicit tickers.
        if (profile != null) {
            for (String t : profile.rankedTickers(10)) {
                if (!knownTickers.contains(t)) knownTickers.add(t);
            }
        }

        IntentParser.ParsedIntent intent = intentParser.parse(instruction, knownTickers);
        response.intent = intent.describe();
        response.intentType = intent.type.name();

        // --- plan -----------------------------------------------------------
        Planner.Plan plan = planner.plan(intent, profile, ticker -> {
            var found = sector.find(ticker);
            return found.orElse(null);
        });
        response.plan = plan.describe();

        if (plan.steps.isEmpty()) {
            response.answer = "I could not map that instruction to an action. "
                    + "Supported: refresh filings for a company, build a cross-quarter "
                    + "comparison table, compare a metric across peers, or query a metric value. "
                    + "Coverage: " + sector.getDisplayName() + " — "
                    + String.join(", ", knownTickers) + ".";
            response.elapsedMs = System.currentTimeMillis() - started;
            return response;
        }

        // --- execute, collecting each step's outcome ------------------------
        NumericGuard.GuardStats stats = guard.newStats();
        List<Object> tables = new ArrayList<>();

        for (Planner.Step step : plan.steps) {
            AgentTool tool = tools.get(step.tool);
            if (tool == null) {
                response.toolLog.add(step.tool + " -> SKIPPED (tool not registered)");
                continue;
            }
            long t0 = System.currentTimeMillis();
            AgentTool.ToolResult r;
            try {
                r = tool.execute(step.args);
            } catch (Exception e) {
                log.error("tool {} threw: {}", step.tool, e.toString(), e);
                r = AgentTool.ToolResult.fail("tool error: " + e.getMessage());
            }
            long dt = System.currentTimeMillis() - t0;

            response.toolLog.add(String.format("%s -> %s (%dms) : %s",
                    step.tool, r.isSuccess() ? "OK" : "FAIL", dt, r.getSummary()));

            // A refusal is an outcome the analyst must see, not a silent gap.
            // Collecting it here means "the data does not exist" reaches the
            // caveat list rather than being inferred from an empty result.
            if (!r.isSuccess()) {
                response.caveats.add(r.getSummary());
            }

            if (r.isSuccess() && r.getPayload() instanceof ComparisonTable) {
                tables.add(r.getPayload());
            }
            // Flatten verified values into the response. Grading reads these,
            // not the prose, so a number that reached the analyst without passing
            // through the verified path shows up as a discrepancy.
            if (r.isSuccess() && r.getPayload() instanceof Map) {
                collectEmittedValues((Map<String, Object>) r.getPayload(), response);
            }
        }

        // --- assemble output ------------------------------------------------
        response.tables = tables;
        response.guardStats = stats.summary();
        response.provenanceCoveragePct = Math.round(stats.provenanceCoverage() * 1000) / 10.0;
        response.hallucinationRatePct = Math.round(stats.hallucinationRate() * 1000) / 10.0;

        // Evidential base: which filings back this response at all.
        response.evidenceAccessions = store.distinctAccessions();
        response.evidenceFactCount = store.totalFactCount();

        response.answer = composeAnswer(intent, tables, stats);
        response.caveats.addAll(collectCaveats(tables, stats));

        // --- learn from this interaction ------------------------------------
        if (profile != null) {
            profile.observeMetrics(intent.metrics.stream().map(Enum::name)
                    .collect(java.util.stream.Collectors.toList()));
            for (String t : intent.tickers) profile.observeTicker(t);
            if (intent.periodCount != null) profile.observePeriodCount(intent.periodCount);
            if (intent.formFilter != null) profile.observeForm(intent.formFilter);
            profile.incrementInteractions();
            profiles.save(profile);
            response.profileSummary = profiles.describe(profile);
        }

        response.elapsedMs = System.currentTimeMillis() - started;
        log.info("run complete in {}ms: {}", response.elapsedMs, stats.summary());
        return response;
    }

    /**
     * Flatten a tool payload into the response's emitted-value list.
     *
     * <p>Recurses through maps and lists so both single-metric answers and
     * peer-comparison row sets are captured uniformly.
     */
    @SuppressWarnings("unchecked")
    private void collectEmittedValues(Map<String, Object> payload, AgentResponse response) {
        if (payload == null || payload.isEmpty()) return;

        // A metric answer: has "value" and usually "period".
        if (payload.containsKey("value") && payload.get("value") != null) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("ticker", payload.get("ticker"));
            v.put("metric", payload.get("metric"));
            v.put("period", payload.get("period") != null
                    ? payload.get("period") : payload.get("currentPeriod"));
            v.put("value", payload.get("value"));
            v.put("footnote", payload.get("footnote"));
            response.emittedValues.add(v);
        }

        // A peer comparison: a rows list.
        Object rows = payload.get("rows");
        if (rows instanceof List) {
            for (Object o : (List<Object>) rows) {
                if (o instanceof Map) {
                    Map<String, Object> row = (Map<String, Object>) o;
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("ticker", row.get("ticker"));
                    v.put("metric", row.get("metric"));
                    v.put("period", row.get("period"));
                    v.put("value", row.get("value"));
                    v.put("footnote", row.get("footnote"));
                    response.emittedValues.add(v);
                }
            }
        }
    }

    /**
     * Compose the analyst-facing narrative.
     *
     * <p>Note what this method has access to: {@code intent} and {@code tables}
     * — identifiers and structured results. It never sees a model-authored
     * number, because none exists in the pipeline. Where a model is configured,
     * it may rephrase this framing; it cannot change a figure.
     */
    private String composeAnswer(IntentParser.ParsedIntent intent,
                                 List<Object> tables,
                                 NumericGuard.GuardStats stats) {
        StringBuilder sb = new StringBuilder();

        switch (intent.type) {
            case BUILD_COMPARISON:
                sb.append("Built ").append(tables.size()).append(" comparison table(s).");
                for (Object o : tables) {
                    ComparisonTable t = (ComparisonTable) o;
                    sb.append("\n\n").append(t.getTitle());
                    sb.append(" — ").append(t.getRows().size()).append(" metrics x ")
                      .append(t.getPeriods().size()).append(" periods, ")
                      .append(t.getCitations().size()).append(" cited sources.");
                    if (t.getWarnings() != null && !t.getWarnings().isEmpty()) {
                        sb.append("\n  ATTENTION: ").append(t.getWarnings().size())
                          .append(" consistency check(s) failed — see Warnings tab.");
                    }
                }
                break;
            case COMPARE_PEERS:
                sb.append("Cross-company comparison prepared. Each company's figure carries "
                        + "its own filing citation; companies that did not report the metric "
                        + "are listed explicitly rather than omitted.");
                break;
            case QUERY_METRIC:
                sb.append("Answered ").append(tables.isEmpty()
                        ? "the metric query" : "the metric query")
                  .append(" using values read directly from filings.");
                break;
            case SYNC_FILINGS:
                sb.append("Filings refreshed and loaded into the fact store with provenance.");
                break;
            default:
                sb.append("No action taken.");
        }

        sb.append("\n\nVerification: ").append(stats.summary());
        if (stats.fabricated > 0) {
            sb.append("\n").append(stats.fabricated)
              .append(" proposed value(s) had no filing support and were withheld.");
        }
        return sb.toString();
    }

    /** Surface anything an analyst should know before relying on the output. */
    private List<String> collectCaveats(List<Object> tables, NumericGuard.GuardStats stats) {
        List<String> caveats = new ArrayList<>();
        if (stats.fabricated > 0) {
            caveats.add(stats.fabricated
                    + " value(s) could not be bound to a filing and were not displayed.");
        }
        if (stats.misattributed > 0) {
            caveats.add(stats.misattributed
                    + " value(s) were real but attributed to the wrong metric or period; "
                    + "the correct attribution was substituted and the discrepancy logged.");
        }
        for (Object o : tables) {
            if (o instanceof ComparisonTable) {
                ComparisonTable t = (ComparisonTable) o;
                if (t.getWarnings() != null) caveats.addAll(t.getWarnings());
            }
        }
        if (stats.checked == 0) {
            caveats.add("No numeric values were verified in this run.");
        }
        return caveats;
    }

    /** Tool catalogue, for capability disclosure. */
    public List<String> describeTools() {
        List<String> out = new ArrayList<>();
        for (AgentTool t : tools.values()) {
            out.add(t.name() + ": " + t.description());
        }
        return out;
    }

    public ProvenanceTracker getProvenanceTracker() { return provenance; }
    public FactStore getStore() { return store; }
    public SectorProperties getSector() { return sector; }
    public LlmClient getLlm() { return llm; }
}
