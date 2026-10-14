package com.persephone.finagent.llm;

import java.util.List;

/**
 * The only interface the harness uses to reach a language model.
 *
 * <p>Deliberately minimal — a single completion call. The harness's correctness
 * does not rest on model capability, because the model is confined to planning
 * and to selecting identifiers. Keeping this interface narrow makes that
 * confinement enforceable: there is no API here for the model to "report a
 * number", so the only path a number can travel is through
 * {@link com.persephone.finagent.guard.NumericGuard}.
 *
 * <p>Two implementations exist: an OpenAI-compatible HTTP client and a
 * deterministic rule engine. The rule engine is not a stub for tests — it makes
 * the framework reproducible and lets a reviewer run the full pipeline without
 * an API key, which matters for both evaluation and for anyone assessing the
 * code.
 */
public interface LlmClient {

    /**
     * Send a structured request and get a completion.
     *
     * @param systemPrompt instructions establishing role and constraints
     * @param userPrompt   the concrete task
     * @param stopSequences optional stop strings
     * @return raw model text, or empty string when unavailable
     */
    String complete(String systemPrompt, String userPrompt, List<String> stopSequences);

    /** Identifier for logging and for the evaluation report. */
    String providerName();

    /** Whether this client produces real model output or deterministic rules. */
    default boolean isDeterministic() { return false; }
}
