package com.persephone.finagent.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The complete result of one harness execution.
 *
 * <p>Carries not just the answer but the reasoning trail: the parsed intent, the
 * plan, per-tool outcomes, verification statistics, and the set of filings
 * backing the response. An analyst auditing a number can see which step produced
 * it; a reviewer assessing the framework can see exactly where the model was
 * involved and where it was not.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentResponse {

    public String instruction;
    public String intent;
    public String intentType;
    public String plan;
    public String answer;
    public String provider;

    /** Human-readable per-step execution log. */
    public List<String> toolLog = new ArrayList<>();

    /** Comparison tables produced, if any. */
    public List<Object> tables = new ArrayList<>();

    /** e.g. "checked=42 matched=42 ... hallucination_rate=0.00%" */
    public String guardStats;
    public double provenanceCoveragePct;
    public double hallucinationRatePct;

    /** Accession numbers of every filing backing this response. */
    public List<String> evidenceAccessions = new ArrayList<>();
    public int evidenceFactCount;

    /** Anything the analyst should know before relying on the output. */
    public List<String> caveats = new ArrayList<>();

    /** The requesting analyst's learned habits after this interaction. */
    public String profileSummary;

    /**
     * Verified values produced by this run, flattened for programmatic
     * consumption.
     *
     * <p>Exists so grading can inspect what the harness <i>emitted</i> rather
     * than what it logged internally. A number that appears only in the prose and
     * not here has escaped the verified path, and that is exactly the condition
     * the evaluation needs to detect.
     */
    public List<Map<String, Object>> emittedValues = new ArrayList<>();

    public long elapsedMs;

    public String getInstruction() { return instruction; }
    public String getIntent() { return intent; }
    public String getIntentType() { return intentType; }
    public String getPlan() { return plan; }
    public String getAnswer() { return answer; }
    public String getProvider() { return provider; }
    public List<String> getToolLog() { return toolLog; }
    public List<Object> getTables() { return tables; }
    public String getGuardStats() { return guardStats; }
    public double getProvenanceCoveragePct() { return provenanceCoveragePct; }
    public double getHallucinationRatePct() { return hallucinationRatePct; }
    public List<String> getEvidenceAccessions() { return evidenceAccessions; }
    public int getEvidenceFactCount() { return evidenceFactCount; }
    public List<String> getCaveats() { return caveats; }
    public String getProfileSummary() { return profileSummary; }
    public List<Map<String, Object>> getEmittedValues() { return emittedValues; }
    public long getElapsedMs() { return elapsedMs; }
}
