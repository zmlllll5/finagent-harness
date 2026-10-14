package com.persephone.finagent;

import com.persephone.finagent.agent.IntentParser;
import com.persephone.finagent.model.Metric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for instruction parsing.
 *
 * <p>Parsing is allowed to be fallible — a misparse produces a visibly wrong
 * report, not a silent data error. But the two cases tested here are not
 * ordinary misparses: they are cases where a missed parse would cause the
 * harness to answer a different question than the one asked, which is the
 * dangerous class of failure.
 */
class IntentParserTest {

    private final IntentParser parser = new IntentParser();
    private final List<String> tickers = Arrays.asList("NVDA", "AMD", "INTC", "AVGO");

    @Test
    @DisplayName("an out-of-range year is parsed so it can be refused, not ignored")
    void outOfRangeYearIsParsedNotIgnored() {
        IntentParser.ParsedIntent i = parser.parse("what was NVDA revenue in FY2099 Q4", tickers);
        assertNotNull(i.period,
                "a future year must be recognised; if it is not, the query silently "
                + "falls back to the latest period and answers a different question");
        assertEquals("FY2099 Q4", i.period);
    }

    @Test
    @DisplayName("a historical year is parsed and kept distinct from annual")
    void historicalYearParsed() {
        IntentParser.ParsedIntent i = parser.parse("NVDA revenue in FY1985 Q3", tickers);
        assertEquals("FY1985 Q3", i.period);
        assertFalse(i.annualRequested);
    }

    @Test
    @DisplayName("a bare year means annual, not a quarter")
    void bareYearMeansAnnual() {
        IntentParser.ParsedIntent i = parser.parse("what was NVDA revenue in FY2026", tickers);
        assertEquals("FY2026", i.period);
        assertFalse(i.annualRequested,
                "a bare FY year is not marked annual by the parser; the query layer "
                + "resolves it against the annual fact");
    }

    @Test
    @DisplayName("'fiscal year NNNN' is recognised as an annual request")
    void spelledOutFiscalYear() {
        IntentParser.ParsedIntent i = parser.parse(
                "what was NVDA total assets in fiscal year 2026", tickers);
        assertEquals("FY2026", i.period);
        assertTrue(i.annualRequested,
                "this flag is what lets the query layer match the annual fact's label");
    }

    @Test
    @DisplayName("analyst synonyms resolve to canonical metrics")
    void synonymsResolveToMetrics() {
        IntentParser.ParsedIntent i = parser.parse(
                "show me NVDA top line and bottom line", tickers);
        assertTrue(i.metrics.contains(Metric.REVENUE),
                "top line should map to revenue");
        assertTrue(i.metrics.contains(Metric.NET_INCOME),
                "bottom line should map to net income");
    }

    @Test
    @DisplayName("a table request is routed to the comparison intent")
    void tableRequestIsRouted() {
        IntentParser.ParsedIntent i = parser.parse(
                "build a 4-quarter comparison table for NVDA with revenue and net income", tickers);
        assertEquals(IntentParser.IntentType.BUILD_COMPARISON, i.type);
        assertEquals(Integer.valueOf(4), i.periodCount);
        assertTrue(i.metrics.contains(Metric.REVENUE));
    }

    @Test
    @DisplayName("a multi-company metric request is routed to peer comparison")
    void peerComparisonIsRouted() {
        IntentParser.ParsedIntent i = parser.parse(
                "compare revenue across NVDA, AMD and INTC", tickers);
        assertEquals(IntentParser.IntentType.COMPARE_PEERS, i.type);
        assertTrue(i.tickers.containsAll(Arrays.asList("NVDA", "AMD", "INTC")));
    }

    @Test
    @DisplayName("tickers are matched on word boundaries, not substrings")
    void tickerMatchingIsNotSubstring() {
        // "AMD" must not be detected inside another word.
        IntentParser.ParsedIntent i = parser.parse("what was AMDPOWER revenue", tickers);
        assertFalse(i.tickers.contains("AMD"),
                "a ticker buried inside a longer token must not match");
    }

    @Test
    @DisplayName("change questions set the right comparison window")
    void changeQuestionTypes() {
        assertEquals("QOQ", parser.parse("NVDA revenue QoQ growth", tickers).questionType);
        assertEquals("YOY", parser.parse("NVDA revenue year over year", tickers).questionType);
        assertEquals("TREND", parser.parse("NVDA revenue trend", tickers).questionType);
        assertEquals("MARGIN", parser.parse("NVDA gross margin", tickers).questionType);
    }

    @Test
    @DisplayName("an unrecognised instruction does not silently become a query")
    void unknownInstructionIsUnknown() {
        IntentParser.ParsedIntent i = parser.parse("hello there", tickers);
        assertEquals(IntentParser.IntentType.UNKNOWN, i.type);
    }
}
