package com.persephone.finagent;

import com.persephone.finagent.config.GuardProperties;
import com.persephone.finagent.guard.NumericGuard;
import com.persephone.finagent.model.FinancialFact;
import com.persephone.finagent.model.Metric;
import com.persephone.finagent.model.Provenance;
import com.persephone.finagent.model.VerifiedValue;
import com.persephone.finagent.provenance.FactStore;
import com.persephone.finagent.provenance.ProvenanceTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the anti-hallucination guarantee.
 *
 * <p>These are the tests that matter most: they assert the framework's central
 * claim rather than its convenience features. If any of these regress, the
 * output can no longer be trusted regardless of how accurate it otherwise is.
 */
class NumericGuardTest {

    private FactStore store;
    private NumericGuard guard;
    private ProvenanceTracker tracker;

    @BeforeEach
    void setUp() {
        store = new FactStore();
        guard = new NumericGuard(store, new GuardProperties());
        tracker = new ProvenanceTracker();

        store.addFacts("NVDA", List.of(
                fact("NVDA", Metric.REVENUE, "FY2026 Q3", "35082000000",
                        "0001045810-25-000230"),
                fact("NVDA", Metric.REVENUE, "FY2027 Q1", "44062000000",
                        "0001045810-26-000052"),
                fact("NVDA", Metric.NET_INCOME, "FY2026 Q3", "19309000000",
                        "0001045810-25-000230")
        ));
    }

    @Test
    @DisplayName("a value that matches a filing fact is verified and carries provenance")
    void exactMatchIsVerified() {
        NumericGuard.VerificationResult r = guard.verify(
                new BigDecimal("35082000000"), "NVDA", Metric.REVENUE, "FY2026 Q3", "Revenue");

        assertEquals(NumericGuard.VisionOutcome.MATCHED, r.outcome);
        assertNotNull(r.value);
        assertEquals(VerifiedValue.VerificationStatus.VERIFIED, r.value.getStatus());
        assertTrue(r.value.isDisplayable());
        assertEquals("0001045810-25-000230",
                r.value.primaryProvenance().getAccessionNumber());
    }

    @Test
    @DisplayName("a fabricated value is rejected and carries no provenance")
    void fabricatedValueIsRejected() {
        // A number that appears nowhere in the store.
        NumericGuard.VerificationResult r = guard.verify(
                new BigDecimal("99887766554"), "NVDA", Metric.REVENUE, "FY2026 Q3", "Revenue");

        assertEquals(NumericGuard.VisionOutcome.FABRICATED, r.outcome);
        assertNull(r.value.getSupportingFact(),
                "an unverified value must not acquire a supporting fact");
        assertFalse(r.value.isDisplayable());
        assertNull(r.value.primaryProvenance());
    }

    @Test
    @DisplayName("a real value under the wrong label is corrected, not silently accepted")
    void misattributedValueIsCorrected() {
        // NVDA did report 19309000000 — but as NET_INCOME, not REVENUE.
        // This is an attribution error, a distinct failure from fabrication.
        NumericGuard.VerificationResult r = guard.verify(
                new BigDecimal("19309000000"), "NVDA", Metric.REVENUE, "FY2026 Q3", "Revenue");

        assertEquals(NumericGuard.VisionOutcome.MISATTRIBUTED, r.outcome);
        assertNotNull(r.correctedFact);
        assertEquals(Metric.NET_INCOME.name(), r.correctedFact.getMetric(),
                "the correction should point at net income, where the value really lives");
        assertTrue(r.value.getChecks().stream().anyMatch(s -> s.contains("MISATTRIBUTION")),
                "the discrepancy must be recorded in the audit trail");
    }

    @Test
    @DisplayName("the strip rule removes unverifiable values from output")
    void stripRuleRemovesFabricatedValues() {
        Optional<VerifiedValue> out = guard.verifyOrDrop(
                new BigDecimal("12345678901"), "NVDA", Metric.REVENUE, "FY2026 Q3", "Revenue");
        assertTrue(out.isEmpty(),
                "with stripping enabled an unsupported number must not reach the analyst");
    }

    @Test
    @DisplayName("a value is not accepted for the wrong period")
    void wrongPeriodIsNotAccepted() {
        // The value exists, but in FY2027 Q1 rather than the requested period.
        NumericGuard.VerificationResult r = guard.verify(
                new BigDecimal("44062000000"), "NVDA", Metric.REVENUE, "FY2026 Q3", "Revenue");

        assertNotEquals(NumericGuard.VisionOutcome.MATCHED, r.outcome,
                "a fact from another period must not satisfy a period-specific request");
    }

    @Test
    @DisplayName("a derived value is recomputed, not trusted")
    void derivedValuesAreRecomputed() {
        // The model claims QoQ growth of 100%; the truth is about 25.6%.
        BigDecimal claimed = new BigDecimal("100");
        BigDecimal prior = new BigDecimal("35082000000");
        BigDecimal current = new BigDecimal("44062000000");
        BigDecimal actual = current.subtract(prior)
                .divide(prior, 6, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        FinancialFact f1 = fact("NVDA", Metric.REVENUE, "FY2027 Q1", "44062000000", "acc-1");
        FinancialFact f2 = fact("NVDA", Metric.REVENUE, "FY2026 Q3", "35082000000", "acc-2");

        VerifiedValue v = guard.verifyDerived("QoQ growth", claimed, actual,
                "PERCENT", "(Q1-Q0)/Q0", List.of(f1, f2));

        assertEquals(0, actual.compareTo(v.getValue()),
                "the authoritative value must be the recomputed one, not the claimed one");
        assertTrue(v.getChecks().stream().anyMatch(s -> s.contains("WARNING")),
                "the disagreement with the model must be logged");
    }

    @Test
    @DisplayName("the balance-sheet identity catches period misalignment")
    void balanceSheetIdentityCatchesMisalignment() {
        store.addFacts("TESTCO", List.of(
                fact("TESTCO", Metric.ASSETS, "FY2026 Q3", "1000", "acc-a"),
                fact("TESTCO", Metric.LIABILITIES, "FY2026 Q3", "400", "acc-a"),
                // Equity from a different period, so the identity cannot hold.
                fact("TESTCO", Metric.STOCKHOLDERS_EQUITY, "FY2026 Q3", "450", "acc-a")
        ));
        String check = guard.checkBalanceSheetIdentity("TESTCO", "FY2026 Q3");
        assertNotNull(check);
        assertTrue(check.contains("FAILED"),
                "1000 != 400 + 450 should be reported as a failed identity check");
    }

    @Test
    @DisplayName("the balance-sheet identity passes when the period is consistent")
    void balanceSheetIdentityPasses() {
        store.addFacts("OKCO", List.of(
                fact("OKCO", Metric.ASSETS, "FY2026 Q3", "1000", "acc-a"),
                fact("OKCO", Metric.LIABILITIES, "FY2026 Q3", "400", "acc-a"),
                fact("OKCO", Metric.STOCKHOLDERS_EQUITY, "FY2026 Q3", "600", "acc-a")
        ));
        String check = guard.checkBalanceSheetIdentity("OKCO", "FY2026 Q3");
        assertNotNull(check);
        assertTrue(check.contains("OK"), "1000 == 400 + 600 should pass: " + check);
    }

    @Test
    @DisplayName("no fact can be constructed without provenance")
    void factWithoutProvenanceIsImpossible() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                FinancialFact.builder()
                        .ticker("NVDA")
                        .metric(Metric.REVENUE.name())
                        .value(new BigDecimal("100"))
                        .build());
        assertTrue(ex.getMessage().contains("provenance"),
                "the type system must reject any fact lacking a source");
    }

    @Test
    @DisplayName("provenance coverage is reported accurately")
    void provenanceCoverageIsMeasured() {
        List<FinancialFact> facts = store.allFacts("NVDA");
        assertEquals(1.0, tracker.coverageRatio(facts), 1e-9,
                "all facts in this store carry auditable provenance");
    }

    private static FinancialFact fact(String ticker, Metric metric, String period,
                                      String value, String accession) {
        return FinancialFact.builder()
                .ticker(ticker)
                .companyCik("0001045810")
                .companyName(ticker + " Inc.")
                .metric(metric.name())
                .sourceConcept("us-gaap:Test")
                .value(new BigDecimal(value))
                .unit("USD")
                .provenance(Provenance.builder()
                        .accessionNumber(accession)
                        .formType("10-Q")
                        .filingDate("2025-01-01")
                        .sourceUrl("https://www.sec.gov/Archives/edgar/data/1045810/"
                                + accession + "/" + accession + "-index.htm")
                        .xbrlTag("us-gaap:Test")
                        .taxonomy("us-gaap")
                        .fiscalPeriod(period)
                        .periodEnd("2025-01-01")
                        .rawText(ticker + " " + metric + " = " + value)
                        .retrievedAt(Instant.now())
                        .retrievalMethod("XBRL_COMPANY_FACTS")
                        .build())
                .build();
    }
}
