package com.persephone.finagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM provider configuration.
 *
 * <p>Supports two providers: {@code openai} (any OpenAI-compatible endpoint) and
 * {@code mock}. The mock provider is a deterministic rule-based planner. It is
 * not a toy: the harness runs end-to-end under it, which matters because it
 * makes the system reproducible for evaluation and lets a reviewer run the whole
 * thing with zero API spend. The provenance and verification layers are
 * provider-independent and active in both modes.
 */
@Component
@ConfigurationProperties(prefix = "finagent.llm")
public class LlmProperties {

    /** openai | mock */
    private String provider = "mock";
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    private String model = "gpt-4o-mini";
    private double temperature = 0.0;
    private int maxTokens = 2048;
    private int timeoutMs = 60000;

    public boolean isMock() {
        return "mock".equalsIgnoreCase(provider) || apiKey == null || apiKey.trim().isEmpty();
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
