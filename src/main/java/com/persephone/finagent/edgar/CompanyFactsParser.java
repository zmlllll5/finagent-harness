package com.persephone.finagent.edgar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.persephone.finagent.config.EdgarProperties;
import com.persephone.finagent.model.CompanyRef;
import com.persephone.finagent.model.FinancialFact;
import com.persephone.finagent.model.Metric;
import com.persephone.finagent.model.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Parses EDGAR XBRL payloads into provenance-carrying {@link FinancialFact}s.
 *
 * <p>Three parsing hazards are handled explicitly, because each one silently
 * corrupts downstream analysis if ignored:
 *
 * <h3>1. YTD vs. quarterly duration facts</h3>
 * EDGAR's {@code companyconcept} for a flow concept (revenue, net income) returns
 * <i>both</i> the quarter-only fact and the year-to-date cumulative fact in the
 * same array, distinguished only by the {@code start}/{@code end} span. Summing
 * the array naively double-counts. We classify by span length: ~90 days is a
 * quarter, ~180/270/365 are cumulatives, which we keep separately and can
 * difference back into a quarter when the quarter-only fact is absent.
 *
 * <h3>2. Point-in-time vs. duration concepts</h3>
 * Balance-sheet items (assets, cash) have no {@code start} — they are
 * instantaneous. Income-statement items are durations. We tag duration type on
 * the fact so comparison logic does not mix them.
 *
 * <h3>3. Restatements</h3>
 * The same period often appears in several filings as a prior-period comparative.
 * <b>The later filing wins</b> — that is the company's most recent word on the
 * number, and it reflects restatements. We keep the winning provenance and
 * record how many competing values were seen, so a restatement is visible rather
 * than hidden.
 */
@Component
public class CompanyFactsParser {

    private static final Logger log = LoggerFactory.getLogger(CompanyFactsParser.class);

    private static final int QUARTER_DAYS_MIN = 70;
    private static final int QUARTER_DAYS_MAX = 110;
    private static final int ANNUAL_DAYS_MIN = 330;
    private static final int ANNUAL_DAYS_MAX = 400;

    private final ObjectMapper mapper = new ObjectMapper();
    private final EdgarProperties props;

    public CompanyFactsParser(EdgarProperties props) {
        this.props = props;
    }

    /** A fact plus bookkeeping about competing values for the same period. */
    public static final class ParsedFact {
        public final FinancialFact fact;
        public final int competingValues;
        public final boolean restated;

        ParsedFact(FinancialFact fact, int competingValues, boolean restated) {
            this.fact = fact;
            this.competingValues = competingValues;
            this.restated = restated;
        }
    }

    /**
     * Parse a {@code companyconcept} response body for one tag.
     *
     * @param body      raw JSON
     * @param company   the company this concept belongs to
     * @param metric    canonical metric this tag maps to
     * @param unitCode  unit filter, typically USD or USD/shares
     */
    public List<ParsedFact> parseConcept(String body, CompanyRef company,
                                         Metric metric, String unitCode) {
        if (body == null || body.isEmpty() || "__NOT_FOUND__".equals(body.trim())) {
            return new ArrayList<>();
        }
        List<ParsedFact> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(body);
            String tag = text(root, "tag");
            String taxonomy = text(root, "taxonomy");
            JsonNode units = root.get("units");
            if (units == null) return out;

            JsonNode series = units.get(unitCode);
            if (series == null) {
                // Fall back to the first available unit rather than dropping the
                // fact entirely; units are surfaced in provenance either way.
                var it = units.fieldNames();
                if (it.hasNext()) series = units.get(it.next());
            }
            if (series == null || !series.isArray()) return out;

            // Group by fiscal period so competing values for the same period are
            // resolved together instead of being emitted as duplicates.
            List<JsonNode> entries = new ArrayList<>();
            series.forEach(entries::add);

            // Latest filing first: the winner of a restatement contest.
            entries.sort(Comparator.comparing(
                    (JsonNode n) -> text(n, "filed"), Comparator.nullsLast(Comparator.reverseOrder())));

            java.util.Map<String, List<JsonNode>> byPeriod = new java.util.LinkedHashMap<>();
            for (JsonNode e : entries) {
                String key = periodKey(e);
                byPeriod.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            }

            for (var group : byPeriod.entrySet()) {
                List<JsonNode> candidates = group.getValue();
                JsonNode chosen = candidates.get(0);
                boolean restated = candidates.size() > 1
                        && distinctValues(candidates) > 1;
                ParsedFact pf = toFact(chosen, company, metric, tag, taxonomy,
                        unitCode, candidates.size(), restated);
                if (pf != null) out.add(pf);
            }
        } catch (Exception e) {
            log.warn("Failed to parse concept payload for {} {}: {}",
                    company.getTicker(), metric, e.toString());
        }
        return out;
    }

    private ParsedFact toFact(JsonNode node, CompanyRef company, Metric metric,
                              String tag, String taxonomy, String unitCode,
                              int competing, boolean restated) {
        JsonNode valNode = node.get("val");
        if (valNode == null || valNode.isNull()) return null;

        BigDecimal value;
        try {
            value = new BigDecimal(valNode.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }

        String start = text(node, "start");
        String end = text(node, "end");
        String accn = text(node, "accn");
        String form = text(node, "form");
        String filed = text(node, "filed");
        Integer fy = intOrNull(node, "fy");
        String fp = text(node, "fp");

        DurationKind kind = classifyDuration(start, end);
        String fiscalPeriod = buildPeriodLabel(fy, fp, kind, end);

        Provenance prov = Provenance.builder()
                .accessionNumber(accn)
                .formType(form)
                .filingDate(filed)
                .sourceUrl(EdgarClient.filingIndexUrl(company.getCik(), accn))
                .xbrlTag(tag)
                .taxonomy(taxonomy)
                .fiscalPeriod(fiscalPeriod)
                .fiscalYear(fy)
                .fiscalQuarter(quarterOf(fp))
                .periodStart(start)
                .periodEnd(end)
                .rawText(buildRawText(company, tag, value, unitCode, start, end, fy, fp))
                .retrievedAt(Instant.now())
                .retrievalMethod("XBRL_COMPANY_FACTS")
                .build();

        FinancialFact fact = FinancialFact.builder()
                .companyCik(company.getCik())
                .ticker(company.getTicker())
                .companyName(company.getName())
                .metric(metric.name())
                .sourceConcept(taxonomy + ":" + tag)
                .value(value)
                .unit(unitCode)
                .provenance(prov)
                .build();

        return new ParsedFact(fact, competing, restated);
    }

    /** Verbatim-ish reconstruction of what the source asserted, for eyeballing. */
    private String buildRawText(CompanyRef company, String tag, BigDecimal value,
                                String unit, String start, String end,
                                Integer fy, String fp) {
        StringBuilder sb = new StringBuilder();
        sb.append(company.getTicker()).append(' ').append(tag)
          .append(" = ").append(value.toPlainString())
          .append(' ').append(unit);
        if (end != null) {
            if (start != null) {
                sb.append(" for period ").append(start).append(" to ").append(end);
            } else {
                sb.append(" as of ").append(end);
            }
        }
        if (fy != null) sb.append(" (FY").append(fy).append(fp == null ? "" : " " + fp).append(')');
        return sb.toString();
    }

    private enum DurationKind { INSTANT, QUARTER, HALF, NINE_MONTH, ANNUAL, OTHER }

    private DurationKind classifyDuration(String start, String end) {
        if (end == null) return DurationKind.OTHER;
        if (start == null) return DurationKind.INSTANT;
        try {
            long days = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end));
            if (days >= QUARTER_DAYS_MIN && days <= QUARTER_DAYS_MAX) return DurationKind.QUARTER;
            if (days > QUARTER_DAYS_MAX && days <= 150) return DurationKind.HALF;
            if (days > 150 && days < ANNUAL_DAYS_MIN) return DurationKind.NINE_MONTH;
            if (days >= ANNUAL_DAYS_MIN && days <= ANNUAL_DAYS_MAX) return DurationKind.ANNUAL;
            return DurationKind.OTHER;
        } catch (Exception e) {
            return DurationKind.OTHER;
        }
    }

    /**
     * Build a period label for a fact.
     *
     * <p>The label must satisfy two requirements that pull in opposite
     * directions, and getting the balance wrong causes real errors downstream:
     *
     * <ol>
     *   <li><b>Round-trip.</b> Whatever is written must be recognised when an
     *       analyst repeats it in a follow-up instruction, or a period that
     *       genuinely exists becomes unqueryable.</li>
     *   <li><b>Homogeneity.</b> A quarter and a year-to-date cumulative for the
     *       same fiscal label must be distinguishable, or a comparison table can
     *       place a 9-month figure in a 3-month column and the error is invisible
     *       because the number is genuine — just for the wrong span.</li>
     * </ol>
     *
     * <p>EDGAR complicates this by labelling annual facts with {@code fp="FY"}
     * for <i>both</i> instantaneous (balance-sheet) and duration
     * (income-statement) concepts, while duration facts additionally carry the
     * fiscal quarter. The annual form is therefore normalised here to a single
     * consistent shape — {@code FY2026 (FY)} — for both kinds, so one
     * period-matching rule covers a whole filer's data. Without this, a
     * balance-sheet annual fact labels as {@code FY2026 FY} while the
     * income-statement annual fact labels as {@code FY2026 (FY)}, and a request
     * for "fiscal year 2026" matches one and is refused for the other.
     */
    private String buildPeriodLabel(Integer fy, String fp, DurationKind kind, String end) {
        boolean annualMarker = fp != null && fp.equalsIgnoreCase("FY");

        String base;
        if (fy != null) {
            if (annualMarker) {
                base = "FY" + fy;
            } else if (fp == null || fp.isEmpty()) {
                base = "FY" + fy;
            } else {
                base = "FY" + fy + " " + fp;
            }
        } else {
            base = end == null ? "unknown" : end;
        }

        switch (kind) {
            case QUARTER:    return base;
            case HALF:       return base + " (H1 YTD)";
            case NINE_MONTH: return base + " (9M YTD)";
            case ANNUAL:     return base + " (FY)";
            case INSTANT:
                // An instantaneous annual figure (a balance-sheet date falling on
                // a fiscal year end) gets the same annual marker, so the label
                // set is uniform across statement types.
                return annualMarker ? base + " (FY)" : base;
            default:         return base;
        }
    }

    private Integer quarterOf(String fp) {
        if (fp == null) return null;
        switch (fp.trim().toUpperCase()) {
            case "Q1": return 1;
            case "Q2": return 2;
            case "Q3": return 3;
            case "Q4": case "FY": return 4;
            default: return null;
        }
    }

    private static String periodKey(JsonNode e) {
        return text(e, "start") + "|" + text(e, "end") + "|" + text(e, "fy") + "|" + text(e, "fp");
    }

    private static int distinctValues(List<JsonNode> nodes) {
        java.util.Set<String> vals = new java.util.HashSet<>();
        for (JsonNode n : nodes) {
            JsonNode v = n.get("val");
            if (v != null) vals.add(v.asText());
        }
        return vals.size();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Integer intOrNull(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return (v == null || v.isNull()) ? null : v.asInt();
    }
}
