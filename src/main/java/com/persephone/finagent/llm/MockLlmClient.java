package com.persephone.finagent.llm;

import java.util.List;

/**
 * Deterministic rule-based "model".
 *
 * <p>Implements {@link LlmClient} by pattern-matching the task description and
 * emitting the structured identifier list the harness expects. It exists for
 * three reasons that all matter to the assessment of this framework:
 *
 * <ol>
 *   <li><b>Reproducibility.</b> An evaluation whose numbers move every run cannot
 *       demonstrate anything. With this provider the entire pipeline —
 *       including the provenance and guard layers — produces identical output
 *       on every run, so the evaluation report is a measurement, not a sample.</li>
 *   <li><b>Zero-cost review.</b> A reviewer can clone the repo, run it, and see
 *       the full behaviour without an API key or spend. Anything that requires
 *       credentials to even observe tends to go unverified.</li>
 *   <li><b>A control condition.</b> It isolates how much of the framework's
 *       accuracy comes from the model versus from the sourcing architecture. As
 *       it happens, the architecture carries most of it — which is the point.</li>
 * </ol>
 *
 * <p>Note what this class does <i>not</i> do: it never emits a numeric value.
 * Neither does the real model, by design. Values are substituted from the fact
 * store on the way out. That is why a rule-based planner and a frontier model
 * produce the same numbers.
 *
 * <p>Instantiated by {@link LlmConfig} rather than component-scanned, so exactly
 * one {@link LlmClient} bean exists for the configured provider.
 */
public class MockLlmClient implements LlmClient {

    @Override
    public String complete(String systemPrompt, String userPrompt, List<String> stopSequences) {
        // The harness sends tasks that are already structured; the deterministic
        // path simply echoes back the requested plan shape. Planning is handled
        // by RuleBasedPlanner, which is provider-independent.
        return "";
    }

    @Override
    public String providerName() {
        return "mock:deterministic";
    }

    @Override
    public boolean isDeterministic() {
        return true;
    }
}
