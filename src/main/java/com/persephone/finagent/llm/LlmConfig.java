package com.persephone.finagent.llm;

import com.persephone.finagent.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the LLM provider at startup.
 *
 * <p>Provider choice is a configuration concern, not a code concern: swapping
 * from a deterministic rule engine to GPT-4o is a one-line change in
 * {@code application.yml} and requires no rebuild of the harness.
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmClient llmClient(LlmProperties props) {
        if (props.isMock()) {
            log.warn("LLM provider = mock (deterministic rules). "
                    + "Set finagent.llm.provider=openai and OPENAI_API_KEY to use a real model. "
                    + "Note: provenance and numeric verification are active in both modes.");
            return new MockLlmClient();
        }
        log.info("LLM provider = openai-compatible, model = {}", props.getModel());
        return new OpenAiCompatibleClient(props);
    }
}
