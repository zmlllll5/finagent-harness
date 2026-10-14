package com.persephone.finagent.cli;

import com.persephone.finagent.agent.AgentHarness;
import com.persephone.finagent.agent.AgentResponse;
import com.persephone.finagent.api.TextRenderer;
import com.persephone.finagent.model.ComparisonTable;
import com.persephone.finagent.model.CompanyRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Non-interactive demo that exercises the full pipeline and prints what it did.
 *
 * <p>Activated with {@code --demo} (or {@code -Dfinagent.demo=true}). It exists
 * so the framework can be assessed without a running HTTP client, and so the
 * provenance machinery is visible in a form that can be read in a terminal —
 * which is where an interviewer or reviewer will first encounter it.
 *
 * <p>The script deliberately includes a deliberately-wrong question at the end:
 * it asks for a quarter that does not exist. The correct behaviour is to refuse,
 * and demonstrating refusal is more convincing than demonstrating a happy path.
 */
@Component
public class DemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    private final AgentHarness harness;
    private final com.persephone.finagent.provenance.FactStore store;
    private final ConfigurableApplicationContext context;

    public DemoRunner(AgentHarness harness,
                      com.persephone.finagent.provenance.FactStore store,
                      ConfigurableApplicationContext context) {
        this.harness = harness;
        this.store = store;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean demo = args.containsOption("demo")
                || "true".equalsIgnoreCase(System.getProperty("finagent.demo"));
        boolean eval = args.containsOption("eval")
                || "true".equalsIgnoreCase(System.getProperty("finagent.eval"));
        if (!demo && !eval) return;

        try {
            if (eval) {
                runEval();
            }
            if (demo) {
                runDemo();
            }
        } catch (Exception e) {
            log.error("runner failed: {}", e.toString(), e);
        } finally {
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }

    /**
     * Run the evaluation suite and print the comparison report.
     *
     * <p>Requires the fact store to be populated, so it warms the cache for the
     * primary company first. The wait is worth it: an evaluation run against an
     * empty store measures nothing.
     */
    private void runEval() {
        banner("EVALUATION — HARNESS vs. NAIVE BASELINE");

        List<CompanyRef> companies = harness.getSector().asCompanyRefs();
        if (companies.isEmpty()) {
            System.out.println("No companies configured; nothing to evaluate.");
            return;
        }
        // Warm the fact store from real filings.
        String focus = companies.get(0).getTicker();
        System.out.println("Loading filings for " + focus + " ...");
        harness.run("sync filings for " + focus, "eval-runner");
        System.out.println("Fact store holds " + store.totalFactCount() + " facts from "
                + store.distinctAccessions().size() + " filings.");
        System.out.println();

        com.persephone.finagent.eval.EvalRunner runner =
                new com.persephone.finagent.eval.EvalRunner(harness, store);

        List<com.persephone.finagent.eval.EvalCase> cases = runner.buildCases();
        System.out.println("Built " + cases.size() + " evaluation cases from the fact store:");
        java.util.Map<String, Integer> byCategory = new java.util.LinkedHashMap<>();
        for (com.persephone.finagent.eval.EvalCase c : cases) {
            byCategory.merge(c.category, 1, Integer::sum);
        }
        for (var e : byCategory.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue());
        }
        System.out.println();

        System.out.println("Running harness ...");
        com.persephone.finagent.eval.EvalReport hReport = runner.evaluateHarness(cases);
        System.out.println("Running baseline ...");
        com.persephone.finagent.eval.EvalReport bReport = runner.evaluateBaseline(cases);

        System.out.println(runner.renderComparison(hReport, bReport));

        // Per-case detail, so a reviewer can see exactly which cases failed.
        System.out.println("Per-case results — harness");
        System.out.println("-".repeat(78));
        for (com.persephone.finagent.eval.EvalCase c : hReport.cases) {
            String mark = c.correct ? "PASS" : "FAIL";
            System.out.printf("  [%s] %-38s %s%n", mark, c.id,
                    c.gradeNote == null ? "" : c.gradeNote);
        }

        // Write machine-readable reports alongside the console output.
        try {
            java.nio.file.Path outDir = java.nio.file.Paths.get("eval-results");
            java.nio.file.Files.createDirectories(outDir);
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            om.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
            om.writeValue(outDir.resolve("harness-report.json").toFile(), hReport);
            om.writeValue(outDir.resolve("baseline-report.json").toFile(), bReport);
            System.out.println();
            System.out.println("Reports written to eval-results/harness-report.json "
                    + "and eval-results/baseline-report.json");
        } catch (Exception e) {
            log.warn("could not write eval reports: {}", e.getMessage());
        }
    }
    private void runDemo() {
        String userId = "analyst-demo";

        banner("FINANCIAL RESEARCH AGENT HARNESS — DEMONSTRATION");
        System.out.println("Sector:  " + harness.getSector().getDisplayName());
        System.out.println("Provider: " + harness.getLlm().providerName());
        System.out.println();
        System.out.println("This demo shows three things in order:");
        System.out.println("  1. Fetching public filings with full provenance");
        System.out.println("  2. Building a cross-quarter comparison with per-cell citations");
        System.out.println("  3. Refusing a question the filings cannot support");
        System.out.println();

        List<CompanyRef> companies = harness.getSector().asCompanyRefs();
        if (companies.isEmpty()) {
            System.out.println("No companies configured. Check finagent.sector.companies.");
            return;
        }
        String focus = companies.get(0).getTicker();

        // --- 1. fetch -------------------------------------------------------
        banner("STEP 1 — FETCH PUBLIC FILINGS (SEC EDGAR)");
        AgentResponse r1 = harness.run("sync filings for " + focus, userId);
        System.out.println("Instruction: sync filings for " + focus);
        System.out.println("Intent:      " + r1.intent);
        System.out.println();
        for (String line : r1.toolLog) System.out.println("  " + line);
        System.out.println();
        System.out.println("Fact store now holds " + r1.evidenceFactCount + " facts");
        System.out.println("Backed by " + r1.evidenceAccessions.size() + " distinct filings:");
        for (String acc : r1.evidenceAccessions) {
            System.out.println("  - " + acc);
        }

        // --- 2. compare -----------------------------------------------------
        banner("STEP 2 — CROSS-QUARTER COMPARISON WITH PER-CELL PROVENANCE");
        AgentResponse r2 = harness.run(
                "build a 4-quarter comparison table for " + focus + " including revenue, "
                        + "gross profit, operating income, net income and free cash flow", userId);
        ComparisonTable table = null;
        for (Object o : r2.tables) {
            if (o instanceof ComparisonTable) { table = (ComparisonTable) o; break; }
        }
        if (table != null) {
            System.out.println(TextRenderer.renderTable(table));
        } else {
            System.out.println("No table produced. Tool log:");
            for (String line : r2.toolLog) System.out.println("  " + line);
        }
        System.out.println("Verification: " + r2.guardStats);
        System.out.println();

        // --- 3. refuse ------------------------------------------------------
        banner("STEP 3 — REFUSAL: A PERIOD THAT DOES NOT EXIST");
        System.out.println("The analyst asks for a future quarter. Correct behaviour is");
        System.out.println("an explicit refusal, not a plausible-looking estimate.");
        System.out.println();
        AgentResponse r3 = harness.run(
                "what was " + focus + " revenue in FY2099 Q4", userId);
        for (String line : r3.toolLog) System.out.println("  " + line);
        System.out.println();
        System.out.println("Caveats raised:");
        if (r3.caveats.isEmpty()) {
            System.out.println("  (none — check that the refusal path fired)");
        } else {
            for (String c : r3.caveats) System.out.println("  - " + c);
        }

        // --- summary --------------------------------------------------------
        banner("SUMMARY");
        System.out.println("Provenance guarantee: every displayed figure is bound to a");
        System.out.println("fact stored from a filing. Nothing is estimated, interpolated,");
        System.out.println("or taken from model memory.");
        System.out.println();
        System.out.println("To export the table with cell-level source comments:");
        System.out.println("  GET /api/export/" + focus + "?format=xlsx");
        System.out.println("To inspect every fact's provenance:");
        System.out.println("  GET /api/facts/" + focus + "/trace");
        System.out.println();
    }

    private static void banner(String title) {
        System.out.println();
        System.out.println("=".repeat(Math.max(70, title.length() + 4)));
        System.out.println("  " + title);
        System.out.println("=".repeat(Math.max(70, title.length() + 4)));
        System.out.println();
    }
}
