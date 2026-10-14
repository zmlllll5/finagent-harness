package com.persephone.finagent;

import com.persephone.finagent.config.EdgarProperties;
import com.persephone.finagent.edgar.CompanyFactsParser;
import com.persephone.finagent.model.CompanyRef;
import com.persephone.finagent.model.Metric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EDGAR payload parsing.
 *
 * <p>These cover the failure modes that are invisible in the output. Every one of
 * them produces a plausible number from a real filing, so nothing downstream can
 * detect the error — only a test at this layer can.
 *
 * <p>Written against captured EDGAR payload shapes rather than the live API, so
 * the suite runs offline and does not depend on SEC availability.
 */
class CompanyFactsParserTest {

    private final CompanyFactsParser parser = new CompanyFactsParser(new EdgarProperties());

    private static final CompanyRef NVDA =
            new CompanyRef("0001045810", "NVDA", "NVIDIA Corporation");

    /**
     * The duration trap: EDGAR returns the quarter-only fact and the
     * year-to-date cumulative in the same array. Their labels must differ, or a
     * comparison table can put a 9-month figure in a 3-month column.
     */
    @Test
    @DisplayName("quarter and year-to-date facts get distinguishable labels")
    void quarterAndYtdAreDistinguished() {
        String payload = conceptJson("us-gaap", "Revenues", "USD",
                entry("2025-01-27", "2025-04-27", 44062000000L, "10-Q", 2027, "Q1", "acc-1"),
                entry("2025-01-27", "2025-10-26", 130000000000L, "10-Q", 2027, "Q3", "acc-2"));

        List<CompanyFactsParser.ParsedFact> facts =
                parser.parseConcept(payload, NVDA, Metric.REVENUE, "USD");

        assertEquals(2, facts.size());
        String q1 = labelOf(facts, "44062000000");
        String ytd = labelOf(facts, "130000000000");

        assertEquals("FY2027 Q1", q1);
        assertTrue(ytd.contains("YTD"),
                "a nine-month cumulative must be labelled so it cannot be mistaken "
                + "for a quarter; got: " + ytd);
    }

    /**
     * The label-consistency trap: EDGAR marks annual facts with fp="FY" for both
     * instantaneous and duration concepts. If those produce different labels, a
     * request for a fiscal year matches one statement type and is refused for the
     * other.
     */
    @Test
    @DisplayName("annual labels are consistent across instant and duration facts")
    void annualLabelsAreConsistent() {
        // Instantaneous: a balance-sheet item, no start date.
        String instantJson = conceptJson("us-gaap", "Assets", "USD",
                entry(null, "2026-01-25", 125000000000L, "10-K", 2026, "FY", "acc-1"));
        // Duration: an income-statement item spanning the fiscal year.
        String durationJson = conceptJson("us-gaap", "Revenues", "USD",
                entry("2025-01-27", "2026-01-25", 130000000000L, "10-K", 2026, "FY", "acc-2"));

        var instantFacts = parser.parseConcept(instantJson, NVDA, Metric.ASSETS, "USD");
        var durationFacts = parser.parseConcept(durationJson, NVDA, Metric.REVENUE, "USD");

        assertEquals(1, instantFacts.size());
        assertEquals(1, durationFacts.size());

        String instantLabel = instantFacts.get(0).fact.getProvenance().getFiscalPeriod();
        String durationLabel = durationFacts.get(0).fact.getProvenance().getFiscalPeriod();

        assertEquals(durationLabel, instantLabel,
                "annual facts must share one label form regardless of statement type, "
                + "or a period that exists becomes unqueryable for one of them");
        assertTrue(instantLabel.contains("FY2026"), "label should name the fiscal year");
    }

    /**
     * The restatement trap: the same period appears in several filings as a
     * prior-period comparative. The most recently filed value must win, and the
     * fact must be marked so the restatement is visible.
     */
    @Test
    @DisplayName("a restatement is resolved to the latest filing and flagged")
    void restatementResolvesToLatestFiling() {
        String payload = conceptJson("us-gaap", "Revenues", "USD",
                entry("2025-01-27", "2025-04-27", 44062000000L, "10-Q", 2027, "Q1",
                        "acc-old", "2026-05-20"),
                entry("2025-01-27", "2025-04-27", 44500000000L, "10-Q", 2027, "Q1",
                        "acc-new", "2026-08-26"));

        var facts = parser.parseConcept(payload, NVDA, Metric.REVENUE, "USD");

        assertEquals(1, facts.size(), "the two entries are the same period and should collapse");
        CompanyFactsParser.ParsedFact f = facts.get(0);
        assertEquals("44500000000", f.fact.getValue().toPlainString(),
                "the later filing is the company's current word on the number");
        assertEquals("acc-new", f.fact.getProvenance().getAccessionNumber());
        assertTrue(f.restated, "the restatement should be visible, not silent");
    }

    @Test
    @DisplayName("every fact carries an auditable provenance")
    void everyFactHasAuditableProvenance() {
        String payload = conceptJson("us-gaap", "Revenues", "USD",
                entry("2025-01-27", "2025-04-27", 44062000000L, "10-Q", 2027, "Q1", "acc-1"));

        var facts = parser.parseConcept(payload, NVDA, Metric.REVENUE, "USD");
        assertEquals(1, facts.size());

        var p = facts.get(0).fact.getProvenance();
        assertTrue(p.isAuditable(), "provenance must be specific enough to check by hand");
        assertNotNull(p.getSourceUrl());
        assertTrue(p.getSourceUrl().contains("sec.gov"),
                "the citation must resolve to a real EDGAR URL, got: " + p.getSourceUrl());
        assertTrue(p.getSourceUrl().contains("acc-1"),
                "the URL should identify the filing it points at");
        assertNotNull(p.getXbrlTag());
        assertNotNull(p.getAccessionNumber());
        assertNotNull(p.getFilingDate());
        assertNotNull(p.getRawText(), "the verbatim fragment lets an analyst eyeball the match");
    }

    @Test
    @DisplayName("an empty or missing payload yields no facts rather than an error")
    void missingPayloadIsNotAnError() {
        assertTrue(parser.parseConcept("", NVDA, Metric.REVENUE, "USD").isEmpty());
        assertTrue(parser.parseConcept("__NOT_FOUND__", NVDA, Metric.REVENUE, "USD").isEmpty());
        assertTrue(parser.parseConcept(null, NVDA, Metric.REVENUE, "USD").isEmpty());
    }

    @Test
    @DisplayName("a malformed payload degrades gracefully")
    void malformedPayloadDegradesGracefully() {
        assertTrue(parser.parseConcept("{\"not\":\"a concept\"}", NVDA, Metric.REVENUE, "USD").isEmpty());
        assertTrue(parser.parseConcept("this is not json at all", NVDA, Metric.REVENUE, "USD").isEmpty());
    }

    // --- helpers -------------------------------------------------------------

    private static String labelOf(List<CompanyFactsParser.ParsedFact> facts, String value) {
        for (CompanyFactsParser.ParsedFact f : facts) {
            if (value.equals(f.fact.getValue().toPlainString())) {
                return f.fact.getProvenance().getFiscalPeriod();
            }
        }
        fail("no fact with value " + value);
        return null;
    }

    private static String entry(String start, String end, long val, String form,
                                int fy, String fp, String accn) {
        return entry(start, end, val, form, fy, fp, accn, "2026-05-20");
    }

    private static String entry(String start, String end, long val, String form,
                                int fy, String fp, String accn, String filed) {
        StringBuilder sb = new StringBuilder("{");
        if (start != null) sb.append("\"start\":\"").append(start).append("\",");
        sb.append("\"end\":\"").append(end).append("\",");
        sb.append("\"val\":").append(val).append(",");
        sb.append("\"accn\":\"").append(accn).append("\",");
        sb.append("\"fy\":").append(fy).append(",");
        sb.append("\"fp\":\"").append(fp).append("\",");
        sb.append("\"form\":\"").append(form).append("\",");
        sb.append("\"filed\":\"").append(filed).append("\"}");
        return sb.toString();
    }

    private static String conceptJson(String taxonomy, String tag, String unit, String... entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"cik\":1045810,\"taxonomy\":\"").append(taxonomy)
          .append("\",\"tag\":\"").append(tag)
          .append("\",\"label\":\"").append(tag)
          .append("\",\"entityName\":\"NVIDIA CORP\",\"units\":{\"")
          .append(unit).append("\":[");
        sb.append(String.join(",", entries));
        sb.append("]}}");
        return sb.toString();
    }
}
