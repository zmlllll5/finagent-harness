package com.persephone.finagent.config;

import com.persephone.finagent.model.CompanyRef;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The research universe under coverage.
 *
 * <p>Deliberately configuration-driven: re-targeting the harness at a different
 * sector is a YAML edit, not a code change. An analyst who moves from
 * semiconductors to, say, utilities should not need a redeploy.
 */
@Component
@ConfigurationProperties(prefix = "finagent.sector")
public class SectorProperties {

    private String id;
    private String displayName;
    private List<CompanyDef> companies = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public List<CompanyDef> getCompanies() { return companies; }
    public void setCompanies(List<CompanyDef> companies) { this.companies = companies; }

    public List<CompanyRef> asCompanyRefs() {
        List<CompanyRef> refs = new ArrayList<>();
        for (CompanyDef c : companies) {
            refs.add(new CompanyRef(c.getCik(), c.getTicker(), c.getName()));
        }
        return refs;
    }

    /** Resolve by ticker or CIK, case-insensitively. */
    public Optional<CompanyRef> find(String tickerOrCik) {
        if (tickerOrCik == null) return Optional.empty();
        String q = tickerOrCik.trim().toUpperCase();
        for (CompanyDef c : companies) {
            if (c.getTicker() != null && c.getTicker().equalsIgnoreCase(q)) {
                return Optional.of(new CompanyRef(c.getCik(), c.getTicker(), c.getName()));
            }
            if (c.getCik() != null && c.getCik().replaceAll("[^0-9]", "").equals(q.replaceAll("[^0-9]", ""))
                    && !q.replaceAll("[^0-9]", "").isEmpty()) {
                return Optional.of(new CompanyRef(c.getCik(), c.getTicker(), c.getName()));
            }
        }
        return Optional.empty();
    }

    public static class CompanyDef {
        private String cik;
        private String ticker;
        private String name;

        public String getCik() { return cik; }
        public void setCik(String cik) { this.cik = cik; }
        public String getTicker() { return ticker; }
        public void setTicker(String ticker) { this.ticker = ticker; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
