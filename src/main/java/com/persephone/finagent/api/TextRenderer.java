package com.persephone.finagent.api;

import com.persephone.finagent.model.ComparisonTable;
import com.persephone.finagent.model.Provenance;

/**
 * Plain-text rendering of a comparison table.
 *
 * <p>Exists for the demo path: an interviewer watching a terminal should be able
 * to see the numbers and the sourcing side by side without opening a spreadsheet.
 * The footnote block is not truncated in this view — the whole point is that the
 * provenance is present and legible.
 */
public final class TextRenderer {

    private TextRenderer() { }

    public static String renderTable(ComparisonTable table) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(table.getTitle()).append('\n');
        sb.append(repeat('=', Math.max(40, table.getTitle().length()))).append('\n');

        if (table.getPeriods() == null || table.getPeriods().isEmpty()) {
            sb.append("(no reporting periods available)\n");
            return sb.toString();
        }

        // Column widths: metric label vs. period columns.
        int metricWidth = 22;
        int colWidth = 14;

        sb.append(pad("Metric", metricWidth));
        for (String p : table.getPeriods()) {
            sb.append(pad(shorten(p, colWidth), colWidth));
        }
        sb.append("Sources\n");
        sb.append(repeat('-', metricWidth + colWidth * table.getPeriods().size() + 8)).append('\n');

        for (ComparisonTable.Row row : table.getRows()) {
            sb.append(pad(shorten(row.getDisplayName(), metricWidth - 1), metricWidth));
            StringBuilder refs = new StringBuilder();
            for (ComparisonTable.Cell cell : row.getCells()) {
                sb.append(pad(shorten(cell.getDisplayValue(), colWidth), colWidth));
                if (cell.getFootnoteRefs() != null) {
                    for (Integer r : cell.getFootnoteRefs()) {
                        refs.append('[').append(r).append(']');
                    }
                }
            }
            sb.append(refs);
            if (hasMissing(row)) {
                sb.append("  (n/r = not reported by the filer)");
            }
            sb.append('\n');
        }

        // Provenance block
        sb.append('\n').append("Data provenance — every figure traced to its filing\n");
        sb.append(repeat('-', 60)).append('\n');
        java.util.List<Provenance> citations = table.getCitations();
        for (int i = 0; i < citations.size(); i++) {
            Provenance p = citations.get(i);
            sb.append("  [").append(i + 1).append("] ")
              .append(table.getCompanyTicker()).append(' ')
              .append(nz(p.getFormType())).append(' ')
              .append(nz(p.getFiscalPeriod()))
              .append(" | ").append(nz(p.getXbrlTag()))
              .append("\n      acc. ").append(nz(p.getAccessionNumber()))
              .append(" | filed ").append(nz(p.getFilingDate()))
              .append(" | period end ").append(nz(p.getPeriodEnd()))
              .append('\n');
            if (p.getRawText() != null) {
                sb.append("      as filed: ").append(p.getRawText()).append('\n');
            }
            if (p.getSourceUrl() != null) {
                sb.append("      verify:   ").append(p.getSourceUrl()).append('\n');
            }
        }

        if (table.getWarnings() != null && !table.getWarnings().isEmpty()) {
            sb.append('\n').append("Consistency warnings\n");
            sb.append(repeat('-', 60)).append('\n');
            for (String w : table.getWarnings()) {
                sb.append("  ! ").append(w).append('\n');
            }
        }

        sb.append('\n');
        sb.append("Coverage: ").append(citations.size())
          .append(" cited sources. Cells marked n/r were not reported by the filer")
          .append(" and were deliberately left empty rather than estimated.\n");
        return sb.toString();
    }

    private static boolean hasMissing(ComparisonTable.Row row) {
        for (ComparisonTable.Cell c : row.getCells()) {
            if ("n/r".equals(c.getDisplayValue())) return true;
        }
        return false;
    }

    private static String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width - 1) + " ";
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
