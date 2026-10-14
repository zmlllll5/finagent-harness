package com.persephone.finagent.eval;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Results of an evaluation run, in the shape the report needs.
 *
 * <p>Reports the metrics that actually matter for the brief's concern. Accuracy
 * alone is uninformative — a system can be accurate and useless if the analyst
 * cannot verify it. So the report separates:
 *
 * <ul>
 *   <li><b>Accuracy</b> — did it get the number right?</li>
 *   <li><b>Provenance coverage</b> — could the analyst check the number?</li>
 *   <li><b>Provenance correctness</b> — did the citation actually point at the
 *       right filing? A citation that exists but is wrong is more dangerous than
 *       no citation, because it manufactures false confidence.</li>
 *   <li><b>Unsupported-claim rate</b> — how often did it assert a number with no
 *       filing behind it?</li>
 *   <li><b>Refusal correctness</b> — on questions with no valid answer, did it
 *       refuse rather than improvise?</li>
 * </ul>
 *
 * <p>Refusal correctness is reported separately because it is the axis on which a
 * naive LLM baseline collapses, and because it is invisible in an accuracy
 * metric: a system that fabricates a plausible answer to an unanswerable
 * question scores identically to one that correctly declines.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvalReport {

    public String systemName;
    public String provider;
    public long startedAtMs;
    public long elapsedMs;
    public int caseCount;

    public int correct;
    public int incorrect;
    public int refusalsCorrect;
    public int refusalsIncorrect;

    public int withProvenance;
    public int withCorrectProvenance;
    public int unsupportedClaims;

    /**
     * Cases where a specific accession number could be expected. Precision is
     * computed against this denominator, not against the total: a structural
     * case such as a multi-company peer comparison spans several filings and has
     * no single correct accession, so including it would depress the metric for
     * a reason that is not a defect in the citation.
     */
    public int casesWithExpectedAccession;

    public final List<EvalCase> cases = new ArrayList<>();
    public final Map<String, String> notes = new LinkedHashMap<>();

    public double accuracy() {
        return caseCount == 0 ? 0.0 : (double) correct / caseCount;
    }

    public double provenanceCoverage() {
        return caseCount == 0 ? 0.0 : (double) withProvenance / caseCount;
    }

    /**
     * Of the values whose citation could be checked against a known filing, what
     * fraction pointed at the right one.
     *
     * <p>This is the metric that matters most for the brief's concern. A citation
     * that exists but names the wrong filing is more dangerous than no citation,
     * because it manufactures false confidence: the analyst clicks through,
     * finds a real document, and stops checking.
     */
    public double provenancePrecision() {
        return casesWithExpectedAccession == 0
                ? 1.0
                : (double) withCorrectProvenance / casesWithExpectedAccession;
    }

    public double unsupportedClaimRate() {
        return caseCount == 0 ? 0.0 : (double) unsupportedClaims / caseCount;
    }

    public String summaryLine() {
        return String.format(
                "%s | n=%d accuracy=%.1f%% provenance_coverage=%.1f%% "
                + "provenance_precision=%.1f%% unsupported_claims=%d refusal_correct=%d/%d",
                systemName, caseCount, accuracy() * 100,
                provenanceCoverage() * 100, provenancePrecision() * 100,
                unsupportedClaims, refusalsCorrect, refusalsCorrect + refusalsIncorrect);
    }
}
