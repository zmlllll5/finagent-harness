package com.persephone.finagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;

/**
 * A precise, auditable pointer back to the primary source of a single fact.
 *
 * <p>This is the backbone of the whole harness. The design rule is deliberately
 * draconian: <b>no number may leave the system without an attached Provenance.</b>
 * That rule is enforced structurally — {@link VerifiedValue} cannot be constructed
 * without one — rather than by convention, because conventions get forgotten
 * under deadline pressure and financial analysts cannot afford a number they
 * cannot check.
 *
 * <p>A Provenance answers four questions an analyst actually asks when auditing
 * a figure:
 * <ol>
 *   <li><i>Which document?</i> — {@code accessionNumber} + {@code formType} +
 *       {@code filingDate} + canonical EDGAR URL.</li>
 *   <li><i>Where inside it?</i> — {@code xbrlTag} for structured facts, or
 *       {@code page}/{@code tableRef} for narrative-derived values.</li>
 *   <li><i>What exactly did the source say?</i> — {@code rawText}, the verbatim
 *       fragment, so a human can eyeball the match without opening the filing.</li>
 *   <li><i>When did we read it, and how?</i> — {@code retrievedAt} +
 *       {@code retrievalMethod}, so staleness and parsing risk are visible.</li>
 * </ol>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Provenance {

    /** SEC EDGAR accession number, e.g. 0001045810-24-000029. Unique per filing. */
    private final String accessionNumber;
    /** Filing form type: 10-K, 10-Q, 8-K ... */
    private final String formType;
    /** Date the filing was accepted by EDGAR (ISO-8601 date). */
    private final String filingDate;
    /** Human-openable link to the filing index on sec.gov. */
    private final String sourceUrl;

    /** XBRL concept name for structured facts, e.g. us-gaap:Revenues. */
    private final String xbrlTag;
    /** XBRL taxonomy namespace prefix, e.g. us-gaap / dei / custom. */
    private final String taxonomy;
    /** Reported fiscal period label, e.g. FY2024 Q3 or FY2023. */
    private final String fiscalPeriod;
    /** Fiscal year the fact belongs to (may differ from filing year). */
    private final Integer fiscalYear;
    /** Fiscal quarter (1-4), null for annual facts. */
    private final Integer fiscalQuarter;
    /** Period start (ISO-8601), for duration facts like revenue. */
    private final String periodStart;
    /** Period end (ISO-8601), the "as of" date. */
    private final String periodEnd;

    /** Page number when the value came from narrative text, else null. */
    private final Integer page;
    /** Table/segment reference when the value came from a rendered table. */
    private final String tableRef;

    /** Verbatim text fragment from the source, for eyeball verification. */
    private final String rawText;

    /** When this harness read the fact. */
    private final Instant retrievedAt;
    /** How it was obtained: XBRL_COMPANY_FACTS | NARRATIVE_PARSE | DERIVED. */
    private final String retrievalMethod;

    private Provenance(Builder b) {
        this.accessionNumber = b.accessionNumber;
        this.formType = b.formType;
        this.filingDate = b.filingDate;
        this.sourceUrl = b.sourceUrl;
        this.xbrlTag = b.xbrlTag;
        this.taxonomy = b.taxonomy;
        this.fiscalPeriod = b.fiscalPeriod;
        this.fiscalYear = b.fiscalYear;
        this.fiscalQuarter = b.fiscalQuarter;
        this.periodStart = b.periodStart;
        this.periodEnd = b.periodEnd;
        this.page = b.page;
        this.tableRef = b.tableRef;
        this.rawText = b.rawText;
        this.retrievedAt = b.retrievedAt == null ? Instant.now() : b.retrievedAt;
        this.retrievalMethod = b.retrievalMethod;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A compact human-readable citation, e.g.
     * {@code NVDA 10-Q FY2025 Q1 (acc. 0001045810-24-000029, filed 2024-05-29)}.
     * Used wherever output is too narrow for the full provenance block.
     */
    public String shortCitation(String ticker) {
        StringBuilder sb = new StringBuilder();
        if (ticker != null) {
            sb.append(ticker).append(' ');
        }
        if (formType != null) {
            sb.append(formType).append(' ');
        }
        if (fiscalPeriod != null) {
            sb.append(fiscalPeriod);
        }
        sb.append(" (acc. ").append(accessionNumber)
          .append(", filed ").append(filingDate).append(')');
        return sb.toString();
    }

    /** True when this pointer is specific enough to be audited by a human. */
    public boolean isAuditable() {
        return accessionNumber != null
                && !accessionNumber.isEmpty()
                && (xbrlTag != null || page != null || tableRef != null);
    }

    public String getAccessionNumber() { return accessionNumber; }
    public String getFormType() { return formType; }
    public String getFilingDate() { return filingDate; }
    public String getSourceUrl() { return sourceUrl; }
    public String getXbrlTag() { return xbrlTag; }
    public String getTaxonomy() { return taxonomy; }
    public String getFiscalPeriod() { return fiscalPeriod; }
    public Integer getFiscalYear() { return fiscalYear; }
    public Integer getFiscalQuarter() { return fiscalQuarter; }
    public String getPeriodStart() { return periodStart; }
    public String getPeriodEnd() { return periodEnd; }
    public Integer getPage() { return page; }
    public String getTableRef() { return tableRef; }
    public String getRawText() { return rawText; }
    public Instant getRetrievedAt() { return retrievedAt; }
    public String getRetrievalMethod() { return retrievalMethod; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Provenance)) return false;
        Provenance p = (Provenance) o;
        return Objects.equals(accessionNumber, p.accessionNumber)
                && Objects.equals(xbrlTag, p.xbrlTag)
                && Objects.equals(periodEnd, p.periodEnd)
                && Objects.equals(tableRef, p.tableRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accessionNumber, xbrlTag, periodEnd, tableRef);
    }

    public static final class Builder {
        private String accessionNumber;
        private String formType;
        private String filingDate;
        private String sourceUrl;
        private String xbrlTag;
        private String taxonomy;
        private String fiscalPeriod;
        private Integer fiscalYear;
        private Integer fiscalQuarter;
        private String periodStart;
        private String periodEnd;
        private Integer page;
        private String tableRef;
        private String rawText;
        private Instant retrievedAt;
        private String retrievalMethod;

        public Builder accessionNumber(String v) { this.accessionNumber = v; return this; }
        public Builder formType(String v) { this.formType = v; return this; }
        public Builder filingDate(String v) { this.filingDate = v; return this; }
        public Builder sourceUrl(String v) { this.sourceUrl = v; return this; }
        public Builder xbrlTag(String v) { this.xbrlTag = v; return this; }
        public Builder taxonomy(String v) { this.taxonomy = v; return this; }
        public Builder fiscalPeriod(String v) { this.fiscalPeriod = v; return this; }
        public Builder fiscalYear(Integer v) { this.fiscalYear = v; return this; }
        public Builder fiscalQuarter(Integer v) { this.fiscalQuarter = v; return this; }
        public Builder periodStart(String v) { this.periodStart = v; return this; }
        public Builder periodEnd(String v) { this.periodEnd = v; return this; }
        public Builder page(Integer v) { this.page = v; return this; }
        public Builder tableRef(String v) { this.tableRef = v; return this; }
        public Builder rawText(String v) { this.rawText = v; return this; }
        public Builder retrievedAt(Instant v) { this.retrievedAt = v; return this; }
        public Builder retrievalMethod(String v) { this.retrievalMethod = v; return this; }

        public Provenance build() { return new Provenance(this); }
    }
}
