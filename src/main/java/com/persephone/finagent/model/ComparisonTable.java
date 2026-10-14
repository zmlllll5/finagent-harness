package com.persephone.finagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A cross-quarter comparison table with per-cell provenance.
 *
 * <p>Shape matches how analysts actually read filings: metric rows, period
 * columns, one company per table (or a company column for peer comparison).
 * Crucially, {@code cells} are not bare numbers — each cell carries the fact
 * that backs it, so the exported spreadsheet can attach a comment or hyperlink
 * pointing at the exact filing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ComparisonTable {

    private final String title;
    private final String companyTicker;
    private final String companyName;
    private final List<String> periods;
    private final List<Row> rows;
    private final List<String> footnotes;
    /** Provenance objects referenced by footnote numbers, index-aligned. */
    private final List<Provenance> citations;
    private final List<String> warnings;

    public ComparisonTable(String title, String companyTicker, String companyName,
                           List<String> periods, List<Row> rows,
                           List<String> footnotes, List<Provenance> citations,
                           List<String> warnings) {
        this.title = title;
        this.companyTicker = companyTicker;
        this.companyName = companyName;
        this.periods = periods;
        this.rows = rows;
        this.footnotes = footnotes;
        this.citations = citations;
        this.warnings = warnings;
    }

    public String getTitle() { return title; }
    public String getCompanyTicker() { return companyTicker; }
    public String getCompanyName() { return companyName; }
    public List<String> getPeriods() { return periods; }
    public List<Row> getRows() { return rows; }
    public List<String> getFootnotes() { return footnotes; }
    public List<Provenance> getCitations() { return citations; }
    public List<String> getWarnings() { return warnings; }

    /** One metric across the requested periods. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Row {
        private final String metric;
        private final String displayName;
        private final List<Cell> cells;

        public Row(String metric, String displayName, List<Cell> cells) {
            this.metric = metric;
            this.displayName = displayName;
            this.cells = cells;
        }

        public String getMetric() { return metric; }
        public String getDisplayName() { return displayName; }
        public List<Cell> getCells() { return cells; }
    }

    /**
     * One data point. {@code footnoteRefs} tie this cell to entries in
     * {@link #getCitations()}, which is what the exporter turns into an XLSX
     * cell comment and a hyperlink.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Cell {
        private final String period;
        private final String displayValue;
        private final String rawValue;
        private final String unit;
        private final List<Integer> footnoteRefs;
        private final String verificationStatus;

        public Cell(String period, String displayValue, String rawValue, String unit,
                    List<Integer> footnoteRefs, String verificationStatus) {
            this.period = period;
            this.displayValue = displayValue;
            this.rawValue = rawValue;
            this.unit = unit;
            this.footnoteRefs = footnoteRefs;
            this.verificationStatus = verificationStatus;
        }

        public String getPeriod() { return period; }
        public String getDisplayValue() { return displayValue; }
        public String getRawValue() { return rawValue; }
        public String getUnit() { return unit; }
        public List<Integer> getFootnoteRefs() { return footnoteRefs; }
        public String getVerificationStatus() { return verificationStatus; }
    }
}
