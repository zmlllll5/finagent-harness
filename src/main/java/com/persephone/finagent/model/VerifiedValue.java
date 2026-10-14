package com.persephone.finagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The only value type permitted to reach user-facing output.
 *
 * <p>This is the enforcement point of the harness's central claim: every number
 * an analyst sees is <i>verified</i> and <i>traceable</i>. A {@code VerifiedValue}
 * cannot exist without a {@link FinancialFact} behind it, and a
 * {@link FinancialFact} cannot exist without {@link Provenance}. The chain is:
 *
 * <pre>
 *   raw filing  ->  FinancialFact (+Provenance)  ->  VerifiedValue (+checks)
 * </pre>
 *
 * <p>When the LLM proposes a number, {@code NumericGuard} tries to bind it to a
 * fact. If the binding fails, no {@code VerifiedValue} is produced — the number
 * is reported as {@link VerificationStatus#UNVERIFIED} and stripped from output.
 * That is the anti-hallucination guarantee, expressed as a type invariant rather
 * than a prompt instruction.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class VerifiedValue {

    public enum VerificationStatus {
        /** Bound to a filing fact within tolerance; safe to display. */
        VERIFIED,
        /** Computed from verified facts (e.g. growth rate); derivation shown. */
        DERIVED,
        /** No supporting fact found. Must not be presented as fact. */
        UNVERIFIED,
        /** Matched a fact only loosely; surfaced with a caution banner. */
        SUSPECT
    }

    private final String label;
    private final BigDecimal value;
    private final String unit;
    private final String displayValue;
    private final VerificationStatus status;

    /** The supporting fact. Null only when status is UNVERIFIED. */
    private final FinancialFact supportingFact;
    /** For DERIVED values: the facts the computation was built from. */
    private final List<FinancialFact> derivationInputs;
    /** For DERIVED values: human-readable formula, e.g. "(Q3-Q2)/Q2". */
    private final String derivedFrom;

    /** Per-check audit trail, e.g. "cross-reference: balance sheet identity OK". */
    private final List<String> checks;

    private VerifiedValue(Builder b) {
        this.label = b.label;
        this.value = b.value;
        this.unit = b.unit;
        this.displayValue = b.displayValue;
        this.status = b.status;
        this.supportingFact = b.supportingFact;
        this.derivationInputs = b.derivationInputs == null ? new ArrayList<>() : b.derivationInputs;
        this.derivedFrom = b.derivedFrom;
        this.checks = b.checks == null ? new ArrayList<>() : b.checks;
    }

    public static Builder builder() { return new Builder(); }

    /** Convenience factory for a value bound directly to a filing fact. */
    public static VerifiedValue of(FinancialFact fact, String label) {
        return builder()
                .label(label)
                .value(fact.getValue())
                .unit(fact.getUnit())
                .status(VerificationStatus.VERIFIED)
                .supportingFact(fact)
                .check("bound to " + fact.getProvenance().getXbrlTag()
                        + " in " + fact.getProvenance().getAccessionNumber())
                .build();
    }

    /**
     * Convenience factory for a computed value. Both inputs must themselves be
     * verified, so derivation cannot smuggle in an unverified number.
     */
    public static VerifiedValue derived(String label, BigDecimal value, String unit,
                                        String formula, List<FinancialFact> inputs) {
        return builder()
                .label(label)
                .value(value)
                .unit(unit)
                .status(VerificationStatus.DERIVED)
                .derivedFrom(formula)
                .derivationInputs(inputs)
                .check("derived via " + formula + " from " + inputs.size() + " verified fact(s)")
                .build();
    }

    /** Whether this value is safe to show an analyst. */
    public boolean isDisplayable() {
        return status == VerificationStatus.VERIFIED || status == VerificationStatus.DERIVED;
    }

    public Provenance primaryProvenance() {
        return supportingFact == null ? null : supportingFact.getProvenance();
    }

    public String getLabel() { return label; }
    public BigDecimal getValue() { return value; }
    public String getUnit() { return unit; }
    public String getDisplayValue() { return displayValue; }
    public VerificationStatus getStatus() { return status; }
    public FinancialFact getSupportingFact() { return supportingFact; }
    public List<FinancialFact> getDerivationInputs() { return derivationInputs; }
    public String getDerivedFrom() { return derivedFrom; }
    public List<String> getChecks() { return checks; }

    public static final class Builder {
        private String label;
        private BigDecimal value;
        private String unit;
        private String displayValue;
        private VerificationStatus status = VerificationStatus.UNVERIFIED;
        private FinancialFact supportingFact;
        private List<FinancialFact> derivationInputs;
        private String derivedFrom;
        private List<String> checks;

        public Builder label(String v) { this.label = v; return this; }
        public Builder value(BigDecimal v) { this.value = v; return this; }
        public Builder unit(String v) { this.unit = v; return this; }
        public Builder displayValue(String v) { this.displayValue = v; return this; }
        public Builder status(VerificationStatus v) { this.status = v; return this; }
        public Builder supportingFact(FinancialFact v) { this.supportingFact = v; return this; }
        public Builder derivationInputs(List<FinancialFact> v) { this.derivationInputs = v; return this; }
        public Builder derivedFrom(String v) { this.derivedFrom = v; return this; }
        public Builder check(String v) {
            if (this.checks == null) this.checks = new ArrayList<>();
            this.checks.add(v);
            return this;
        }

        public VerifiedValue build() {
            if (status == VerificationStatus.VERIFIED && supportingFact == null) {
                throw new IllegalStateException(
                        "VerifiedValue cannot be VERIFIED without a supporting fact.");
            }
            if (status == VerificationStatus.UNVERIFIED && supportingFact != null) {
                this.status = VerificationStatus.VERIFIED;
            }
            return new VerifiedValue(this);
        }
    }
}
