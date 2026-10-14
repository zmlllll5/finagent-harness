package com.persephone.finagent.api;

import com.persephone.finagent.agent.AgentHarness;
import com.persephone.finagent.agent.AgentResponse;
import com.persephone.finagent.agent.TableExporter;
import com.persephone.finagent.model.ComparisonTable;
import com.persephone.finagent.model.CompanyRef;
import com.persephone.finagent.model.FinancialFact;
import com.persephone.finagent.model.Provenance;
import com.persephone.finagent.provenance.FactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST surface for the harness.
 *
 * <p>Endpoints are shaped around the analyst's workflow rather than around the
 * internal object model: ask a question, get a comparison table, export it with
 * citations, inspect where a number came from.
 *
 * <p>{@code GET /facts/{ticker}/trace} is the one worth pointing at — it returns
 * every fact held for a company with its full provenance, which is the endpoint
 * an analyst (or a skeptical reviewer) uses to answer "prove it".
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentHarness harness;
    private final FactStore store;

    public AgentController(AgentHarness harness, FactStore store) {
        this.harness = harness;
        this.store = store;
    }

    /** Run a natural-language instruction. */
    @PostMapping("/agent/query")
    public AgentResponse query(@RequestBody QueryRequest request) {
        String instruction = request.getInstruction();
        String userId = request.getUserId();
        log.info("query: user={} instruction={}", userId, instruction);
        return harness.run(instruction, userId);
    }

    /** Coverage: which companies and which metrics are available to query. */
    @GetMapping("/coverage")
    public Map<String, Object> coverage() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sector", harness.getSector().getId());
        out.put("sectorName", harness.getSector().getDisplayName());
        out.put("provider", harness.getLlm().providerName());

        List<Map<String, Object>> companies = new ArrayList<>();
        for (CompanyRef c : harness.getSector().asCompanyRefs()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticker", c.getTicker());
            row.put("name", c.getName());
            row.put("cik", c.paddedCik());
            row.put("loaded", store.isLoaded(c.getTicker()));
            row.put("metricsAvailable", store.availableMetrics(c.getTicker()));
            companies.add(row);
        }
        out.put("companies", companies);
        out.put("totalFactsLoaded", store.totalFactCount());
        out.put("tools", harness.describeTools());
        return out;
    }

    /**
     * Full provenance trace for a company: every fact with the exact filing
     * location it came from. This is the audit endpoint.
     */
    @GetMapping("/facts/{ticker}/trace")
    public Map<String, Object> trace(@PathVariable String ticker,
                                     @RequestParam(required = false) String metric) {
        String t = ticker.toUpperCase();
        List<FinancialFact> facts = store.allFacts(t);
        if (metric != null) {
            List<FinancialFact> filtered = new ArrayList<>();
            for (FinancialFact f : facts) {
                if (f.getMetric().equalsIgnoreCase(metric)) filtered.add(f);
            }
            facts = filtered;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (FinancialFact f : facts) {
            Provenance p = f.getProvenance();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metric", f.getMetric());
            row.put("value", f.getValue().toPlainString());
            row.put("unit", f.getUnit());
            row.put("period", p.getFiscalPeriod());
            row.put("periodEnd", p.getPeriodEnd());
            row.put("formType", p.getFormType());
            row.put("filed", p.getFilingDate());
            row.put("xbrlTag", p.getXbrlTag());
            row.put("taxonomy", p.getTaxonomy());
            row.put("accessionNumber", p.getAccessionNumber());
            row.put("sourceUrl", p.getSourceUrl());
            row.put("sourceText", p.getRawText());
            row.put("retrievalMethod", p.getRetrievalMethod());
            row.put("retrievedAt", p.getRetrievedAt() == null ? null : p.getRetrievedAt().toString());
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticker", t);
        out.put("factCount", rows.size());
        out.put("distinctFilings", store.distinctAccessions());
        out.put("facts", rows);
        return out;
    }

    /**
     * Export the most recent comparison table for a company as CSV or XLSX.
     *
     * <p>Re-runs the comparison rather than caching one, so the export always
     * reflects the current fact store and its citations stay consistent with
     * what a fresh query would produce.
     */
    @GetMapping("/export/{ticker}")
    public ResponseEntity<byte[]> export(@PathVariable String ticker,
                                         @RequestParam(defaultValue = "xlsx") String format,
                                         @RequestParam(defaultValue = "4") int periods,
                                         @RequestParam(required = false) String userId) throws IOException {
        String t = ticker.toUpperCase();
        AgentResponse response = harness.run(
                "build a " + periods + "-quarter comparison table for " + t, userId);

        ComparisonTable table = null;
        for (Object o : response.tables) {
            if (o instanceof ComparisonTable) { table = (ComparisonTable) o; break; }
        }
        if (table == null) {
            return ResponseEntity.badRequest()
                    .body(("No comparison table could be built for " + t
                            + ". Run /api/agent/query first to load its filings. "
                            + "Details: " + String.join("; ", response.toolLog))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        Path outDir = Paths.get(System.getProperty("java.io.tmpdir"), "finagent-exports");
        java.nio.file.Files.createDirectories(outDir);
        boolean xlsx = !"csv".equalsIgnoreCase(format);
        Path target = outDir.resolve(t + "-comparison." + (xlsx ? "xlsx" : "csv"));

        if (xlsx) {
            TableExporter.toXlsx(table, target);
        } else {
            TableExporter.toCsv(table, target);
        }

        byte[] bytes = java.nio.file.Files.readAllBytes(target);
        String contentType = xlsx
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + target.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }

    /** Plain-text rendering of a comparison table, for terminal demonstration. */
    @GetMapping(value = "/compare/{ticker}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String compareText(@PathVariable String ticker,
                              @RequestParam(defaultValue = "4") int periods,
                              @RequestParam(required = false) String userId) {
        String t = ticker.toUpperCase();
        AgentResponse response = harness.run(
                "build a " + periods + "-quarter comparison table for " + t, userId);
        ComparisonTable table = null;
        for (Object o : response.tables) {
            if (o instanceof ComparisonTable) { table = (ComparisonTable) o; break; }
        }
        if (table == null) {
            return "Could not build a table for " + t + "\n" + String.join("\n", response.toolLog);
        }
        return TextRenderer.renderTable(table);
    }

    /** Request body for {@code POST /api/agent/query}. */
    public static class QueryRequest {
        private String instruction;
        private String userId;

        public String getInstruction() { return instruction; }
        public void setInstruction(String instruction) { this.instruction = instruction; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
    }
}
