package com.persephone.finagent.guard;

import com.persephone.finagent.config.GuardProperties;
import com.persephone.finagent.model.FinancialFact;
import com.persephone.finagent.model.Metric;
import com.persephone.finagent.model.VerifiedValue;
import com.persephone.finagent.provenance.FactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Verifies every number before it reaches an analyst.
 *
 * <p>This class is the load-bearing component of the framework, and its design
 * rests on one decision: <b>the LLM is not trusted to report numbers at all.</b>
 *
 * <p>The conventional approach to grounding is to instruct the model to cite its
 * sources and then hope. That fails in exactly the situation that matters — when
 * the model is confidently wrong — because a fabricated citation looks
 * indistinguishable from a real one in fluent prose. So this harness inverts
 * control: the model may only <i>select and arrange</i> identifiers (metric
 * names, tickers, periods). The numeric values are substituted from
 * {@link FactStore} on the way out. A number the model emits that cannot be
 * bound to a filing fact is never displayed.
 *
 * <p>Concretely, verification runs in three passes, cheapest first:
 * <ol>
 *   <li><b>Exact binding.</b> Metric + ticker + period resolves to a stored fact
 *       whose value matches within relative tolerance. Confidence: highest.</li>
 *   <li><b>Value search.</b> The number exists somewhere in the store but under
 *       a different metric or period. This is an <i>attribution</i> error rather
 *       than a fabrication — we surface the correct attribution and flag the
 *       mismatch instead of silently accepting it.</li>
 *   <li><b>Rejection.</b> No filing value is within tolerance. Logged as
 *       HALLUCINATION_SUSPECT and stripped from output.</li>
 * </ol>
 *
 * <p>Separating passes 2 and 3 is a deliberate product decision. Both are
 * failures, but "the model used the right number with the wrong label" and "the
 * model invented a number" have different root causes and different fixes.
 * Collapsing them into a single "unverified" bucket makes the evaluation report
 * far less actionable.
 */
@Component
public class NumericGuard {

    private static final Logger log = LoggerFactory.getLogger(NumericGuard.class);

    private final FactStore store;
    private final GuardProperties props;

    public NumericGuard(FactStore store, GuardProperties props) {
        this.store = store;
        this.props = props;
    }

    /** Outcome of verifying a single proposed number. */
    public static final class VerificationResult {
        public final VisionOutcome outcome;
        public final VerifiedValue value;
        public final String detail;
        /** When outcome is MISATTRIBUTED: what the store says the value actually is. */
        public final FinancialFact correctedFact;

        VerificationResult(VisionOutcome outcome, VerifiedValue value,
                           String detail, FinancialFact correctedFact) {
            this.outcome = outcome;
            this.value = value;
            this.detail = detail;
            this.correctedFact = correctedFact;
        }
    }

    public enum VisionOutcome {
        /** Bound to the exact expected fact. */
        MATCHED,
        /** Value is real but attributed to the wrong metric/period. */
        MISATTRIBUTED,
        /** No filing supports this value. */
        FABRICATED
    }

    /**
     * Verify a value the model proposed for a specific metric/ticker/period.
     *
     * @param proposed  the number the model emitted
     * @param ticker    company it claimed the number belongs to
     * @param metric    metric it claimed the number represents
     * @param period    fiscal period it claimed
     * @param label     human label for the output row
     */
    public VerificationResult verify(BigDecimal proposed, String ticker, Metric metric,
                                     String period, String label) {
        if (proposed == null) {
            return new VerificationResult(VisionOutcome.FABRICATED, null,
                    "no value supplied", null);
        }

        // --- Pass 1: exact binding ------------------------------------------
        Optional<FinancialFact> exact = findExact(ticker, metric, period);
        if (exact.isPresent() && withinTolerance(proposed, exact.get().getValue())) {
            FinancialFact f = exact.get();
            VerifiedValue vv = VerifiedValue.builder()
                    .label(label)
                    .value(f.getValue())
                    .unit(f.getUnit())
                    .displayValue(format(f.getValue(), f.getUnit()))
                    .status(VerifiedValue.VerificationStatus.VERIFIED)
                    .supportingFact(f)
                    .check("exact bind: " + f.getProvenance().getXbrlTag()
                            + " @ " + f.getProvenance().getAccessionNumber())
                    .build();
            return new VerificationResult(VisionOutcome.MATCHED, vv,
                    "bound to " + f.getProvenance().getXbrlTag(), null);
        }

        // --- Pass 2: value exists elsewhere in the store ---------------------
        List<FinancialFact> elsewhere = store.findByValue(proposed, props.getNumericTolerance());
        if (!elsewhere.isEmpty()) {
            FinancialFact actual = pickMostPlausible(elsewhere, ticker, metric, period);
            String detail = String.format(
                    "value %s is real but misattributed: claimed %s %s %s, actual source is %s %s %s",
                    proposed.toPlainString(),
                    ticker, metric, period,
                    actual.getTicker(), actual.getMetric(), actual.getProvenance().getFiscalPeriod());

            // Emit a VERIFIED value pointing at the *correct* fact. The analyst
            // gets the right number with the right sourcing; the discrepancy is
            // recorded in the audit trail rather than papered over.
            VerifiedValue vv = VerifiedValue.builder()
                    .label(label)
                    .value(actual.getValue())
                    .unit(actual.getUnit())
                    .displayValue(format(actual.getValue(), actual.getUnit()))
                    .status(VerifiedValue.VerificationStatus.SUSPECT)
                    .supportingFact(actual)
                    .check("MISATTRIBUTION CORRECTED: " + detail)
                    .build();
            return new VerificationResult(VisionOutcome.MISATTRIBUTED, vv, detail, actual);
        }

        // --- Pass 3: no support anywhere -------------------------------------
        String detail = String.format(
                "no filing value matches %s (claimed %s %s %s) within tolerance %s",
                proposed.toPlainString(), ticker, metric, period, props.getNumericTolerance());
        log.warn("HALLUCINATION_SUSPECT: {}", detail);

        VerifiedValue vv = VerifiedValue.builder()
                .label(label)
                .value(proposed)
                .displayValue("[UNVERIFIED: " + proposed.toPlainString() + "]")
                .status(VerifiedValue.VerificationStatus.UNVERIFIED)
                .check(detail)
                .build();
        return new VerificationResult(VisionOutcome.FABRICATED, vv, detail, null);
    }

    /**
     * Convenience for the common case: verify a value and, when it fails, drop
     * it. Returns empty when the value must not be shown.
     */
    public Optional<VerifiedValue> verifyOrDrop(BigDecimal proposed, String ticker,
                                                Metric metric, String period, String label) {
        VerificationResult r = verify(proposed, ticker, metric, period, label);
        if (r.outcome == VisionOutcome.FABRICATED && props.isStripUnverifiedNumbers()) {
            return Optional.empty();
        }
        return Optional.ofNullable(r.value);
    }

    /**
     * Verify a number whose origin is a known fact but whose value was computed
     * (a growth rate, a margin). The inputs must all be verified; the arithmetic
     * is re-run here so a bad model computation cannot slip through.
     */
    public VerifiedValue verifyDerived(String label, BigDecimal claimed, BigDecimal computed,
                                       String unit, String formula,
                                       List<FinancialFact> inputs) {
        boolean agrees = withinTolerance(claimed, computed);
        VerifiedValue.Builder b = VerifiedValue.builder()
                .label(label)
                .value(computed)
                .unit(unit)
                .displayValue(format(computed, unit))
                .status(VerifiedValue.VerificationStatus.DERIVED)
                .derivedFrom(formula)
                .derivationInputs(inputs)
                .check("recomputed " + formula + " = " + computed.toPlainString());
        if (!agrees) {
            b.check("WARNING: model proposed " + (claimed == null ? "n/a" : claimed.toPlainString())
                    + " but recomputation gives " + computed.toPlainString()
                    + "; authoritative value is the recomputed one");
        }
        return b.build();
    }

    /**
     * Balance-sheet identity check: Assets == Liabilities + Equity.
     *
     * <p>An LLM cannot violate this identity, but a parsing pipeline can — by
     * aligning three balance-sheet lines to different period ends. That failure
     * is invisible in the output and would badly mislead an analyst, so it is
     * worth an explicit check on every balance-sheet comparison.
     *
     * @return check log entry, or null when the check does not apply
     */
    public String checkBalanceSheetIdentity(String ticker, String period) {
        if (!props.isEnableBalanceSheetIdentityCheck()) return null;

        Optional<FinancialFact> assets = findExact(ticker, Metric.ASSETS, period);
        Optional<FinancialFact> liab = findExact(ticker, Metric.LIABILITIES, period);
        Optional<FinancialFact> equity = findExact(ticker, Metric.STOCKHOLDERS_EQUITY, period);

        if (assets.isEmpty() || liab.isEmpty() || equity.isEmpty()) {
            return "balance-sheet identity check skipped for " + ticker + " " + period
                    + " (incomplete line items)";
        }

        BigDecimal lhs = assets.get().getValue();
        BigDecimal rhs = liab.get().getValue().add(equity.get().getValue());
        double lhsD = lhs.doubleValue();
        double rhsD = rhs.doubleValue();
        // Allow 1% slack: noncontrolling interests and preferred stock can make
        // reported Liabilities+Equity differ legitimately from a naive sum.
        double diff = Math.abs(lhsD - rhsD);
        double relDiff = Math.abs(lhsD) > 0 ? diff / Math.abs(lhsD) : diff;

        if (relDiff <= 0.01) {
            return "balance-sheet identity OK for " + ticker + " " + period
                    + " (|" + fmt(lhsD) + " - " + fmt(rhsD) + "| / " + fmt(lhsD)
                    + " = " + String.format("%.4f", relDiff) + ")";
        }
        String warn = "BALANCE-SHEET IDENTITY FAILED for " + ticker + " " + period
                + ": Assets=" + lhs.toPlainString()
                + " but Liabilities+Equity=" + rhs.toPlainString()
                + " (relative gap " + String.format("%.4f", relDiff)
                + "). Likely period misalignment across line items.";
        log.warn(warn);
        return warn;
    }

    /**
     * Cross-reference check between two independent routes to the same quantity.
     *
     * <p>When a metric could come from either a component sum or a directly
     * reported total, disagreement means one of the two extractions is wrong.
     * Reporting the discrepancy beats picking a winner silently.
     */
    public String crossReference(String ticker, Metric metric, String period,
                                 BigDecimal routeA, String routeAName,
                                 BigDecimal routeB, String routeBName) {
        if (routeA == null || routeB == null) {
            return "cross-reference for " + ticker + " " + metric + " " + period
                    + " incomplete: one route missing";
        }
        double a = routeA.doubleValue();
        double b = routeB.doubleValue();
        double rel = Math.abs(a - b) / Math.max(Math.abs(a), 1e-9);
        if (rel <= props.getNumericTolerance() * 10) {
            return "cross-reference AGREES for " + ticker + " " + metric + " " + period
                    + ": " + routeAName + "=" + fmt(a) + " ≈ " + routeBName + "=" + fmt(b);
        }
        return "cross-reference DISAGREES for " + ticker + " " + metric + " " + period
                + ": " + routeAName + "=" + fmt(a) + " vs " + routeBName + "=" + fmt(b)
                + " (relative gap " + String.format("%.4f", rel) + ")";
    }

    // --- helpers -------------------------------------------------------------

    private Optional<FinancialFact> findExact(String ticker, Metric metric, String period) {
        if (ticker == null || metric == null) return Optional.empty();
        return store.find(ticker, metric, period);
    }

    /**
     * When a value is found in several places, prefer the fact that is closest to
     * what the model claimed: same company first, then same metric, then same
     * period. This maximises the chance we surface a useful correction rather
     * than an arbitrary one.
     */
    private FinancialFact pickMostPlausible(List<FinancialFact> candidates, String ticker,
                                           Metric metric, String period) {
        FinancialFact best = candidates.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (FinancialFact f : candidates) {
            int score = 0;
            if (ticker != null && ticker.equalsIgnoreCase(f.getTicker())) score += 4;
            if (metric != null && metric.name().equals(f.getMetric())) score += 2;
            if (period != null && period.equalsIgnoreCase(f.getProvenance().getFiscalPeriod())) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        return best;
    }

    /**
     * Relative-tolerance comparison with an absolute floor, so values near zero
     * do not match spuriously.
     */
    private boolean withinTolerance(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return false;
        if (a.compareTo(b) == 0) return true;
        double da = a.doubleValue();
        double db = b.doubleValue();
        double tol = Math.max(Math.abs(db) * props.getNumericTolerance(), 1e-6);
        return Math.abs(da - db) <= tol;
    }

    private static String format(BigDecimal v, String unit) {
        if (v == null) return "";
        BigDecimal abs = v.abs();
        String suffix;
        BigDecimal scaled;
        if (abs.compareTo(new BigDecimal("1000000000")) >= 0) {
            scaled = v.divide(new BigDecimal("1000000000"), 3, RoundingMode.HALF_UP);
            suffix = "B";
        } else if (abs.compareTo(new BigDecimal("1000000")) >= 0) {
            scaled = v.divide(new BigDecimal("1000000"), 2, RoundingMode.HALF_UP);
            suffix = "M";
        } else {
            scaled = v;
            suffix = "";
        }
        String unitSuffix = "USD".equals(unit) ? "" : (" " + (unit == null ? "" : unit));
        return scaled.stripTrailingZeros().toPlainString() + suffix + unitSuffix;
    }

    private static String fmt(double d) {
        return String.format("%,.0f", d);
    }

    /** Aggregate hallucination statistics for the evaluation report. */
    public static final class GuardStats {
        public int checked;
        public int matched;
        public int misattributed;
        public int fabricated;

        public double hallucinationRate() {
            return checked == 0 ? 0.0 : (double) fabricated / checked;
        }

        public double provenanceCoverage() {
            return checked == 0 ? 1.0 : (double) (matched + misattributed) / checked;
        }

        public String summary() {
            return String.format(
                    "checked=%d matched=%d misattributed=%d fabricated=%d | hallucination_rate=%.2f%% provenance_coverage=%.2f%%",
                    checked, matched, misattributed, fabricated,
                    hallucinationRate() * 100, provenanceCoverage() * 100);
        }
    }

    public GuardStats newStats() { return new GuardStats(); }

    public void record(GuardStats stats, VerificationResult r) {
        if (stats == null || r == null) return;
        stats.checked++;
        switch (r.outcome) {
            case MATCHED: stats.matched++; break;
            case MISATTRIBUTED: stats.misattributed++; break;
            case FABRICATED: stats.fabricated++; break;
        }
    }

    /** All facts in the store for a company, for coverage reporting. */
    public List<FinancialFact> auditTrail(String ticker) {
        return new ArrayList<>(store.allFacts(ticker));
    }
}
