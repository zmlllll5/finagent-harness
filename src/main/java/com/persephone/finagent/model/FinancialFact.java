package com.persephone.finagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A single quantitative fact extracted from a filing — plus the chain of custody
 * that produced it.
 *
 * <p>A {@code FinancialFact} is the atomic unit the whole harness reasons over.
 * The LLM never invents numbers; it only ever <i>selects and arranges</i> facts
 * that already exist in this collection. {@link VerifiedValue} is the only
 * container that reaches user-facing output, and it can only be built from a
 * fact that carries {@link Provenance}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class FinancialFact {

    private final String companyCik;
    private final String ticker;
    private final String companyName;

    /** Canonical metric key, normalised across companies, e.g. REVENUE, NET_INCOME. */
    private final String metric;
    /** The original XBRL concept this was mapped from, e.g. us-gaap:Revenues. */
    private final String sourceConcept;

    /** Numeric value exactly as reported, in {@link #unit}. */
    private final BigDecimal value;
    private final String unit;

    private final Provenance provenance;

    private FinancialFact(Builder b) {
        this.companyCik = b.companyCik;
        this.ticker = b.ticker;
        this.companyName = b.companyName;
        this.metric = b.metric;
        this.sourceConcept = b.sourceConcept;
        this.value = b.value;
        this.unit = b.unit;
        this.provenance = b.provenance;
    }

    public static Builder builder() { return new Builder(); }

    /**
     * Key used to align the same metric across companies and periods when
     * building comparison tables.
     */
    public String seriesKey() {
        return companyCik + "|" + metric + "|" + provenance.getFiscalPeriod();
    }

    public String getCompanyCik() { return companyCik; }
    public String getTicker() { return ticker; }
    public String getCompanyName() { return companyName; }
    public String getMetric() { return metric; }
    public String getSourceConcept() { return sourceConcept; }
    public BigDecimal getValue() { return value; }
    public String getUnit() { return unit; }
    public Provenance getProvenance() { return provenance; }

    @Override
    public String toString() {
        return String.format("%s %s %s = %s %s [%s]",
                ticker, metric, provenance.getFiscalPeriod(), value, unit,
                provenance.getAccessionNumber());
    }

    public static final class Builder {
        private String companyCik;
        private String ticker;
        private String companyName;
        private String metric;
        private String sourceConcept;
        private BigDecimal value;
        private String unit;
        private Provenance provenance;

        public Builder companyCik(String v) { this.companyCik = v; return this; }
        public Builder ticker(String v) { this.ticker = v; return this; }
        public Builder companyName(String v) { this.companyName = v; return this; }
        public Builder metric(String v) { this.metric = v; return this; }
        public Builder sourceConcept(String v) { this.sourceConcept = v; return this; }
        public Builder value(BigDecimal v) { this.value = v; return this; }
        public Builder unit(String v) { this.unit = v; return this; }
        public Builder provenance(Provenance v) { this.provenance = v; return this; }

        public FinancialFact build() {
            List<String> missing = new ArrayList<>();
            if (metric == null) missing.add("metric");
            if (value == null) missing.add("value");
            if (provenance == null) missing.add("provenance");
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "FinancialFact is missing required field(s): " + missing
                        + ". Facts without provenance are not permitted in this harness.");
            }
            return new FinancialFact(this);
        }
    }
}
