package com.persephone.finagent.provenance;

import com.persephone.finagent.model.FinancialFact;
import com.persephone.finagent.model.Provenance;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a set of facts into a numbered citation list, and audits their coverage.
 *
 * <p>Analysts reading a table want the numbers first and the sourcing available
 * on demand, not interleaved. So the pattern here is: each fact gets a footnote
 * number, the number appears inline in the table, and the full provenance block
 * is emitted once at the bottom. The same numbering drives the XLSX export,
 * where footnote N becomes a cell comment and a hyperlink on the cell itself.
 *
 * <p>This class also produces the <b>provenance coverage</b> metric used in
 * evaluation: the fraction of emitted data points that carry an auditable
 * pointer. A framework that answers fluently but sources 60% of its numbers is
 * not fit for financial use, and this is where that becomes measurable.
 */
@Component
public class ProvenanceTracker {

    /** Result of registering a batch of facts. */
    public static final class CitationSet {
        private final Map<Provenance, Integer> numbering = new LinkedHashMap<>();
        private final List<Provenance> ordered = new ArrayList<>();
        private final Map<String, Integer> byFactKey = new LinkedHashMap<>();

        public int register(FinancialFact fact) {
            Provenance p = fact.getProvenance();
            Integer existing = numbering.get(p);
            if (existing != null) {
                byFactKey.put(keyOf(fact), existing);
                return existing;
            }
            int n = ordered.size() + 1;
            numbering.put(p, n);
            ordered.add(p);
            byFactKey.put(keyOf(fact), n);
            return n;
        }

        /** Footnote number for a fact, or null when it was never registered. */
        public Integer refFor(FinancialFact fact) {
            return byFactKey.get(keyOf(fact));
        }

        public List<Provenance> citations() {
            return new ArrayList<>(ordered);
        }

        public int size() {
            return ordered.size();
        }

        private static String keyOf(FinancialFact f) {
            Provenance p = f.getProvenance();
            return p.getAccessionNumber() + "|" + p.getXbrlTag() + "|" + p.getPeriodEnd();
        }
    }

    public CitationSet newCitationSet() {
        return new CitationSet();
    }

    /**
     * Render one provenance as a human-readable footnote line.
     *
     * <p>Written for an analyst under time pressure: source document first, then
     * the precise locator, then the verbatim fragment, then a URL they can click.
     */
    public String renderFootnote(int number, Provenance p, String ticker) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(number).append("] ");
        sb.append(ticker == null ? "" : ticker + " ");
        sb.append(p.getFormType() == null ? "" : p.getFormType() + " ");
        sb.append(p.getFiscalPeriod() == null ? "" : p.getFiscalPeriod());
        sb.append(" — ").append(p.getXbrlTag() == null ? "narrative" : p.getXbrlTag());
        sb.append(" | acc. ").append(p.getAccessionNumber());
        sb.append(" | filed ").append(p.getFilingDate());
        if (p.getPeriodEnd() != null) {
            sb.append(" | period end ").append(p.getPeriodEnd());
        }
        if (p.getRawText() != null) {
            sb.append("\n      source text: ").append(p.getRawText());
        }
        if (p.getSourceUrl() != null) {
            sb.append("\n      verify: ").append(p.getSourceUrl());
        }
        return sb.toString();
    }

    /**
     * Coverage ratio over a set of facts: fraction with an auditable provenance.
     * Used both as a runtime health signal and as an evaluation metric.
     */
    public double coverageRatio(List<FinancialFact> facts) {
        if (facts == null || facts.isEmpty()) return 1.0;
        int auditable = 0;
        for (FinancialFact f : facts) {
            Provenance p = f.getProvenance();
            if (p != null && p.isAuditable()) auditable++;
        }
        return (double) auditable / facts.size();
    }

    /**
     * Build the full citation block as text, for console/report rendering.
     */
    public String renderCitationBlock(CitationSet set, Map<Provenance, String> tickersByProvenance) {
        if (set == null || set.size() == 0) {
            return "  (no sources — this output carries no filing evidence)";
        }
        StringBuilder sb = new StringBuilder();
        List<Provenance> citations = set.citations();
        for (int i = 0; i < citations.size(); i++) {
            Provenance p = citations.get(i);
            String ticker = tickersByProvenance == null ? null : tickersByProvenance.get(p);
            sb.append("  ").append(renderFootnote(i + 1, p, ticker));
            if (i < citations.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }
}
