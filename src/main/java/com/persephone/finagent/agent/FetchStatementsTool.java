package com.persephone.finagent.agent;

import com.persephone.finagent.edgar.FilingFetcher;
import com.persephone.finagent.model.CompanyRef;
import com.persephone.finagent.model.FinancialFact;
import com.persephone.finagent.model.Metric;
import com.persephone.finagent.profile.ProfileService;
import com.persephone.finagent.profile.UserProfile;
import com.persephone.finagent.provenance.FactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tool: fetch and load a company's financial statements into the fact store.
 *
 * <p>Every fact it loads carries full {@link com.persephone.finagent.model.Provenance}.
 * This tool is the <i>only</i> way facts enter the system, which is what makes
 * the guarantee "everything downstream is traceable" hold by construction rather
 * than by discipline.
 */
@Component
public class FetchStatementsTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(FetchStatementsTool.class);

    public static final String NAME = "fetchStatements";

    private final FilingFetcher fetcher;
    private final FactStore store;
    private final ProfileService profiles;

    public FetchStatementsTool(FilingFetcher fetcher, FactStore store, ProfileService profiles) {
        this.fetcher = fetcher;
        this.store = store;
        this.profiles = profiles;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "Fetch a company's public financial statements from SEC EDGAR XBRL data "
                + "and load them into the fact store with full source provenance. "
                + "Args: company (CompanyRef), metrics (list of Metric, optional), "
                + "userId (optional, for habit adaptation).";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> args) {
        Object companyArg = args.get("company");
        if (!(companyArg instanceof CompanyRef)) {
            return ToolResult.fail("fetchStatements requires a CompanyRef in 'company'");
        }
        CompanyRef company = (CompanyRef) companyArg;

        List<Metric> metrics = FilingFetcher.defaultMetricSet();
        Object metricsArg = args.get("metrics");
        if (metricsArg instanceof List) {
            List<Metric> requested = new ArrayList<>();
            for (Object o : (List<Object>) metricsArg) {
                if (o instanceof Metric) requested.add((Metric) o);
            }
            if (!requested.isEmpty()) metrics = requested;
        }

        // Habit adaptation: if the analyst has a stable metric set, fetch it,
        // but always union with the default set so a habitual selection can never
        // hide a fact the analyst might need.
        String userId = (String) args.get("userId");
        if (userId != null) {
            UserProfile profile = profiles.getOrCreate(userId);
            for (String m : profile.rankedMetrics(8)) {
                Metric habitual = Metric.fromName(m).orElse(null);
                if (habitual != null && !metrics.contains(habitual)) {
                    metrics.add(habitual);
                }
            }
        }

        long start = System.currentTimeMillis();
        Map<String, List<FinancialFact>> facts = fetcher.fetchCompanyFacts(company, metrics);
        long elapsed = System.currentTimeMillis() - start;

        int total = 0;
        for (List<FinancialFact> series : facts.values()) {
            store.addFacts(company.getTicker(), series);
            total += series.size();
        }

        if (total == 0) {
            return ToolResult.fail("No facts retrieved for " + company.getTicker()
                    + " — check network access to data.sec.gov and the User-Agent setting.");
        }

        String summary = String.format(
                "Loaded %d facts across %d metrics for %s in %dms; all carry filing-level provenance",
                total, facts.size(), company.getTicker(), elapsed);
        log.info(summary);
        return ToolResult.ok(summary, facts);
    }
}
