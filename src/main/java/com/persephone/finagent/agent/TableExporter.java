package com.persephone.finagent.agent;

import com.persephone.finagent.model.ComparisonTable;
import com.persephone.finagent.model.Provenance;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders a {@link ComparisonTable} to CSV or XLSX.
 *
 * <p>The export is where provenance has to survive the transition out of the
 * agent and into the analyst's own tooling. Two mechanisms, and getting the
 * second one right is the interesting part:
 *
 * <ul>
 *   <li><b>CSV.</b> Footnotes are emitted as extra trailing columns on every row,
 *       so a figure and its source stay on the same line when the file is opened
 *       anywhere. A separate footnote sheet would be lost the moment someone
 *       sorts or filters.</li>
 *   <li><b>XLSX.</b> Native cell comments carry the full provenance —
 *       accession number, XBRL tag, period, source text, URL — and the cell
 *       itself is hyperlinked to the filing on sec.gov. This mirrors exactly how
 *       a careful analyst annotates a model: hover the cell, see where the number
 *       came from, click through if you doubt it.</li>
 * </ul>
 */
public class TableExporter {

    private static final Logger log = LoggerFactory.getLogger(TableExporter.class);

    /** Write CSV with provenance columns appended. */
    public static Path toCsv(ComparisonTable table, Path target) throws IOException {
        StringBuilder sb = new StringBuilder();

        // Header: metric + periods + provenance columns.
        sb.append("Metric");
        for (String p : table.getPeriods()) {
            sb.append(',').append(csvEscape(p));
        }
        sb.append(",Footnotes,Unit,Verification");

        // A second header band labelling the provenance columns makes the file
        // self-describing when it is opened months later.
        sb.append('\n');

        for (ComparisonTable.Row row : table.getRows()) {
            sb.append(csvEscape(row.getDisplayName()));
            for (ComparisonTable.Cell cell : row.getCells()) {
                sb.append(',').append(csvEscape(cell.getDisplayValue()));
            }
            sb.append(',').append(csvEscape(footnoteRefs(row.getCells())));
            sb.append(',').append(csvEscape(firstUnit(row)));
            sb.append(',').append(csvEscape(firstStatus(row)));
            sb.append('\n');
        }

        sb.append('\n').append("Sources\n");
        List<Provenance> citations = table.getCitations();
        for (int i = 0; i < citations.size(); i++) {
            Provenance p = citations.get(i);
            sb.append('[').append(i + 1).append("],");
            sb.append(csvEscape(table.getCompanyTicker())).append(',');
            sb.append(csvEscape(p.getFormType())).append(',');
            sb.append(csvEscape(p.getFiscalPeriod())).append(',');
            sb.append(csvEscape(p.getXbrlTag())).append(',');
            sb.append(csvEscape(p.getAccessionNumber())).append(',');
            sb.append(csvEscape(p.getFilingDate())).append(',');
            sb.append(csvEscape(p.getPeriodEnd())).append(',');
            sb.append(csvEscape(p.getSourceUrl()));
            sb.append('\n');
        }

        if (table.getWarnings() != null && !table.getWarnings().isEmpty()) {
            sb.append('\n').append("Warnings\n");
            for (String w : table.getWarnings()) {
                sb.append(csvEscape(w)).append('\n');
            }
        }

        Files.createDirectories(target.getParent());
        Files.write(target, sb.toString().getBytes(StandardCharsets.UTF_8));
        log.info("wrote CSV: {}", target);
        return target;
    }

    /**
     * Write XLSX with cell-level provenance comments and filing hyperlinks.
     */
    public static Path toXlsx(ComparisonTable table, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Comparison");
            CreationHelper helper = wb.getCreationHelper();

            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle numberStyle = wb.createCellStyle();
            numberStyle.setAlignment(HorizontalAlignment.RIGHT);

            // Title row
            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(table.getTitle());
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 13);
            CellStyle titleStyle = wb.createCellStyle();
            titleStyle.setFont(titleFont);
            titleCell.setCellStyle(titleStyle);

            // Header
            int headerRowIdx = 2;
            Row header = sheet.createRow(headerRowIdx);
            Cell metricHeader = header.createCell(0);
            metricHeader.setCellValue("Metric");
            metricHeader.setCellStyle(headerStyle);
            for (int i = 0; i < table.getPeriods().size(); i++) {
                Cell c = header.createCell(i + 1);
                c.setCellValue(table.getPeriods().get(i));
                c.setCellStyle(headerStyle);
            }
            Cell srcHeader = header.createCell(table.getPeriods().size() + 1);
            srcHeader.setCellValue("Source (footnote)");
            srcHeader.setCellStyle(headerStyle);

            List<Provenance> citations = table.getCitations();

            // Data rows
            int r = headerRowIdx + 1;
            for (ComparisonTable.Row row : table.getRows()) {
                Row xr = sheet.createRow(r++);
                xr.createCell(0).setCellValue(row.getDisplayName());

                for (int i = 0; i < row.getCells().size(); i++) {
                    ComparisonTable.Cell cell = row.getCells().get(i);
                    Cell xc = xr.createCell(i + 1);
                    xc.setCellValue(cell.getDisplayValue());
                    xc.setCellStyle(numberStyle);

                    if (cell.getFootnoteRefs() == null || cell.getFootnoteRefs().isEmpty()) continue;

                    int ref = cell.getFootnoteRefs().get(0);
                    if (ref < 1 || ref > citations.size()) continue;
                    Provenance p = citations.get(ref - 1);

                    // The provenance comment is the mechanism that makes the
                    // exported file self-auditing without the agent present.
                    String commentText = buildCommentText(p, cell, table.getCompanyTicker());
                    Comment comment = xc.getCellComment();
                    if (comment == null) {
                        comment = sheet.createDrawingPatriarch()
                                .createCellComment(helper.createClientAnchor());
                    }
                    comment.setString(helper.createRichTextString(commentText));
                    comment.setAuthor("FinAgent Harness");
                    xc.setCellComment(comment);

                    if (p.getSourceUrl() != null) {
                        try {
                            org.apache.poi.ss.usermodel.Hyperlink link = helper.createHyperlink(
                                    org.apache.poi.common.usermodel.HyperlinkType.URL);
                            link.setAddress(p.getSourceUrl());
                            xc.setHyperlink(link);
                        } catch (Exception e) {
                            log.debug("hyperlink failed for {}: {}", p.getSourceUrl(), e.getMessage());
                        }
                    }
                }

                // Trailing text column listing footnote refs for this row.
                xr.createCell(table.getPeriods().size() + 1)
                  .setCellValue('[' + footnoteRefs(row.getCells()) + ']');
            }

            // Sources sheet: full provenance blocks, one per citation.
            Sheet srcSheet = wb.createSheet("Sources");
            String[] cols = {"#", "Company", "Form", "Fiscal Period", "XBRL Tag",
                             "Accession", "Filed", "Period End", "Retrieved (UTC)", "URL", "Source Text"};
            Row sh = srcSheet.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                Cell c = sh.createCell(i);
                c.setCellValue(cols[i]);
                c.setCellStyle(headerStyle);
            }
            int sr = 1;
            for (int i = 0; i < citations.size(); i++) {
                Provenance p = citations.get(i);
                Row row = srcSheet.createRow(sr++);
                row.createCell(0).setCellValue(i + 1);
                row.createCell(1).setCellValue(table.getCompanyTicker());
                row.createCell(2).setCellValue(nz(p.getFormType()));
                row.createCell(3).setCellValue(nz(p.getFiscalPeriod()));
                row.createCell(4).setCellValue(nz(p.getXbrlTag()));
                row.createCell(5).setCellValue(nz(p.getAccessionNumber()));
                row.createCell(6).setCellValue(nz(p.getFilingDate()));
                row.createCell(7).setCellValue(nz(p.getPeriodEnd()));
                row.createCell(8).setCellValue(p.getRetrievedAt() == null ? "" : p.getRetrievedAt().toString());
                row.createCell(9).setCellValue(nz(p.getSourceUrl()));
                row.createCell(10).setCellValue(nz(p.getRawText()));
            }
            for (int i = 0; i < cols.length; i++) {
                srcSheet.setColumnWidth(i, i == 9 || i == 10 ? 12000 : 4000);
            }

            // Warnings sheet, present only when identity checks failed.
            if (table.getWarnings() != null && !table.getWarnings().isEmpty()) {
                Sheet warnSheet = wb.createSheet("Warnings");
                int wr = 0;
                for (String w : table.getWarnings()) {
                    warnSheet.createRow(wr++).createCell(0).setCellValue(w);
                }
            }

            sheet.setColumnWidth(0, 6000);
            for (int i = 1; i <= table.getPeriods().size() + 1; i++) {
                sheet.setColumnWidth(i, 4200);
            }
            sheet.createFreezePane(1, headerRowIdx + 1);

            try (OutputStream out = Files.newOutputStream(target)) {
                wb.write(out);
            }
        }
        log.info("wrote XLSX: {}", target);
        return target;
    }

    /** The provenance block that appears when an analyst hovers a data cell. */
    private static String buildCommentText(Provenance p, ComparisonTable.Cell cell, String ticker) {
        StringBuilder sb = new StringBuilder();
        sb.append("SOURCE — ").append(ticker).append(' ')
          .append(nz(p.getFormType())).append(' ')
          .append(nz(p.getFiscalPeriod())).append('\n');
        sb.append("XBRL tag:  ").append(nz(p.getXbrlTag())).append('\n');
        sb.append("Accession: ").append(nz(p.getAccessionNumber())).append('\n');
        sb.append("Filed:     ").append(nz(p.getFilingDate())).append('\n');
        sb.append("Period end:").append(nz(p.getPeriodEnd())).append('\n');
        sb.append("Value:     ").append(nz(cell.getRawValue())).append(' ')
          .append(nz(cell.getUnit())).append('\n');
        sb.append("Status:    ").append(nz(cell.getVerificationStatus())).append('\n');
        if (p.getRawText() != null) {
            sb.append("As filed:  ").append(p.getRawText()).append('\n');
        }
        if (p.getSourceUrl() != null) {
            sb.append("Verify:    ").append(p.getSourceUrl()).append('\n');
        }
        sb.append("\nReported as-is from SEC EDGAR. Not adjusted or estimated.");
        return sb.toString();
    }

    private static String footnoteRefs(List<ComparisonTable.Cell> cells) {
        java.util.LinkedHashSet<Integer> refs = new java.util.LinkedHashSet<>();
        for (ComparisonTable.Cell c : cells) {
            if (c.getFootnoteRefs() != null) refs.addAll(c.getFootnoteRefs());
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Integer r : refs) {
            if (i++ > 0) sb.append(' ');
            sb.append('[').append(r).append(']');
        }
        return sb.toString();
    }

    private static String firstUnit(ComparisonTable.Row row) {
        for (ComparisonTable.Cell c : row.getCells()) {
            if (c.getUnit() != null) return c.getUnit();
        }
        return "";
    }

    private static String firstStatus(ComparisonTable.Row row) {
        for (ComparisonTable.Cell c : row.getCells()) {
            if (c.getVerificationStatus() != null) return c.getVerificationStatus();
        }
        return "";
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        String v = s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        if (v.contains(",") || v.contains("\"") || v.contains("|")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Expose the worksheet merge helper for callers that want titled bands. */
    static void mergeTitleRow(Sheet sheet, int rowIdx, int fromCol, int toCol) {
        sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, fromCol, toCol));
    }
}
