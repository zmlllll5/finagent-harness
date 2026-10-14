package com.persephone.finagent.eval;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A single evaluation case: a question, the ground truth, and how it was graded.
 *
 * <p>Ground truth here is derived from the filings themselves, not authored by
 * hand. That matters: hand-written expected values drift from the source as
 * companies amend filings, and a benchmark whose answers are wrong is worse than
 * no benchmark. Values are pinned to an accession number so a restatement is
 * detectable rather than a silent failure.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvalCase {

    /** Stable id, e.g. "nvda-revenue-fy2026q3". */
    public String id;
    /** The natural-language instruction sent to the agent. */
    public String instruction;
    /** Ticker the case concerns. */
    public String ticker;
    /** Metric name, matching {@link com.persephone.finagent.model.Metric}. */
    public String metric;
    /** Fiscal period the answer must concern, or null for "latest". */
    public String period;
    /**
     * Expected value, or null when the case tests a refusal (the correct answer
     * is that no such value exists).
     */
    public BigDecimal expectedValue;
    /** Whether the correct behaviour is to answer with a number. */
    public boolean expectsAnswer = true;
    /** Filing the expectation was derived from, for reproducibility. */
    public String sourceAccession;
    /** Why this case exists — what failure it is designed to catch. */
    public String rationale;
    /** Category, used to group results in the report. */
    public String category = "accuracy";

    // --- graded outcome ------------------------------------------------------
    /** Value the harness returned, if any. */
    public BigDecimal actualValue;
    /** Whether the harness refused. */
    public boolean refused;
    /** Whether the answer matched ground truth. */
    public boolean correct;
    /** Did the response carry an auditable provenance for the value? */
    public boolean hasProvenance;
    /** Whether the cited accession matches the expected one. */
    public boolean provenanceCorrect;
    /** Free-text note on how grading went. */
    public String gradeNote;

    public static List<EvalCase> list() { return new ArrayList<>(); }
}
