package com.persephone.finagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.persephone.finagent.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * OpenAI-compatible chat completions client.
 *
 * <p>Speaks the plain {@code /chat/completions} protocol with no vendor SDK, so
 * it works against OpenAI, Azure-style gateways, and the many OpenAI-compatible
 * Chinese providers without code changes — only {@code base-url} moves.
 *
 * <p>The system prompt is used to <i>reduce</i> the rate of bad model output,
 * never as the mechanism that makes output safe. Prompt instructions are
 * advisory; the guard layer is authoritative. This distinction is the whole
 * reason the framework can tolerate a weak or adversarial model.
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private final LlmProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleClient(LlmProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, List<String> stopSequences) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.getModel());
            body.put("temperature", props.getTemperature());
            body.put("max_tokens", props.getMaxTokens());

            ArrayNode messages = body.putArray("messages");
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt == null ? "" : systemPrompt);
            ObjectNode usr = messages.addObject();
            usr.put("role", "user");
            usr.put("content", userPrompt == null ? "" : userPrompt);

            if (stopSequences != null && !stopSequences.isEmpty()) {
                ArrayNode stops = body.putArray("stop");
                for (String s : stopSequences) stops.add(s);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl().replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                log.error("LLM call failed: HTTP {} — {}", response.statusCode(),
                        truncate(response.body(), 400));
                return "";
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() == 0) {
                log.warn("LLM response contained no choices");
                return "";
            }
            JsonNode content = choices.get(0).path("message").path("content");
            return content.isMissingNode() ? "" : content.asText();

        } catch (Exception e) {
            log.error("LLM call threw: {}", e.toString());
            return "";
        }
    }

    @Override
    public String providerName() {
        return "openai-compatible:" + props.getModel();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
