package com.hackathon.agent.ai.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.hackathon.agent.ai.llm.LlmProviderException;
import com.hackathon.agent.ai.llm.LlmProviderException.Category;
import com.hackathon.agent.ai.llm.LlmRequest;
import com.hackathon.agent.ai.llm.LlmResponse;
import com.hackathon.agent.ai.llm.LlmUsage;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the real SDK against a local HTTP server; the Anthropic API is never called.
 */
class AnthropicLlmProviderTest {

    private static final String KEY = "placeholder-key";
    private static final LlmRequest REQUEST = new LlmRequest("the system prompt", "the user prompt",
            Map.of("type", "object", "properties", Map.of("a", Map.of("type", "string")), "required", List.of("a"),
                    "additionalProperties", false));

    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private int status = 200;
    private String response = message("end_turn", "{\\\"a\\\": \\\"b\\\"}");
    private Map<String, String> headers = Map.of();
    private boolean hang;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/messages", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keys.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (hang) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void convertsRequestAndResponse() {
        LlmResponse answer = provider("medium").generate(REQUEST);

        assertThat(answer.text()).isEqualTo("{\"a\": \"b\"}");
        assertThat(answer.provider()).isEqualTo("anthropic");
        assertThat(answer.model()).isEqualTo("model-that-answered");
        // Cached input is part of the input.
        assertThat(answer.usage()).isEqualTo(new LlmUsage(100 + 20 + 3, 45));

        JsonNode body = JsonMapper.builder().build().readTree(bodies.getFirst());
        assertThat(body.get("model").stringValue()).isEqualTo("configured-model");
        assertThat(body.get("max_tokens").intValue()).isEqualTo(1234);
        assertThat(body.get("system").stringValue()).isEqualTo("the system prompt");
        assertThat(body.get("messages").size()).isEqualTo(1);
        assertThat(body.get("messages").get(0).get("content").toString()).contains("the user prompt");
        assertThat(body.get("output_config").get("effort").stringValue()).isEqualTo("medium");
        assertThat(body.get("output_config").get("format").get("type").stringValue()).isEqualTo("json_schema");
        assertThat(body.get("output_config").get("format").get("schema").get("additionalProperties").booleanValue()).isFalse();
        // Not accepted by all models, so never sent.
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("thinking")).isFalse();
        assertThat(keys).containsExactly("Bearer " + KEY);
    }

    @Test
    void effortIsOptional() {
        provider("").generate(REQUEST);

        assertThat(bodies.getFirst()).doesNotContain("effort");
    }

    @ParameterizedTest
    @CsvSource({
            "400, invalid_request_error, INVALID_REQUEST", "401, authentication_error, AUTHENTICATION",
            "402, billing_error, QUOTA_EXCEEDED", "403, permission_error, AUTHENTICATION",
            "404, not_found_error, INVALID_REQUEST", "408, timeout_error, TIMEOUT",
            "413, request_too_large, INVALID_REQUEST", "429, rate_limit_error, RATE_LIMITED",
            "500, api_error, UNAVAILABLE", "503, api_error, UNAVAILABLE", "529, overloaded_error, UNAVAILABLE"})
    void mapsErrorsWithoutRetryingOrExposingDetails(int code, String type, Category expected) {
        status = code;
        response = """
                {"type": "error", "error": {"type": "%s", "message": "secret detail from the user prompt"}}""".formatted(type);

        LlmProviderException error = failure();

        assertThat(error.category()).isEqualTo(expected);
        assertThat(error.getMessage()).contains("HTTP " + code).doesNotContain("secret detail").doesNotContain(KEY);
        assertThat(bodies).hasSize(1);
    }

    @Test
    void rateLimitCarriesTheRequestedWaitingTime() {
        status = 429;
        headers = Map.of("retry-after", "42");
        response = "{\"type\": \"error\", \"error\": {\"type\": \"rate_limit_error\", \"message\": \"slow down\"}}";

        assertThat(failure().retryAfter()).contains(Duration.ofSeconds(42));
    }

    @Test
    void spendLimitIsNotARateLimit() {
        status = 429;
        headers = Map.of("x-should-retry", "false");
        response = "{\"type\": \"error\", \"error\": {\"type\": \"rate_limit_error\", \"message\": \"spend limit\"}}";

        assertThat(failure().category()).isEqualTo(Category.QUOTA_EXCEEDED);
    }

    @Test
    void timeout() {
        hang = true;

        LlmProviderException error = catchThrowableOfType(LlmProviderException.class,
                () -> provider("", 1000).generate(REQUEST));

        assertThat(error.category()).isEqualTo(Category.TIMEOUT);
        assertThat(bodies).hasSize(1);
    }

    @Test
    void unreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AnthropicLlmProvider provider = provider("", 30000, "http://127.0.0.1:" + closedPort);

        assertThatThrownBy(() -> provider.generate(REQUEST)).isInstanceOfSatisfying(LlmProviderException.class,
                e -> assertThat(e.category()).isEqualTo(Category.UNAVAILABLE));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"this is not JSON", "{}", "{\"type\": \"message\", \"content\": 5}"})
    void malformedResponse(String body) {
        response = body;

        assertThat(failure().category()).isEqualTo(Category.INVALID_RESPONSE);
    }

    @Test
    void emptyAnswer() {
        response = message("end_turn", "");

        assertThat(failure().category()).isEqualTo(Category.INVALID_RESPONSE);
    }

    @ParameterizedTest
    @CsvSource({"refusal, REFUSED", "max_tokens, TRUNCATED", "model_context_window_exceeded, TRUNCATED"})
    void incompleteAnswersAreRejectedButTheirTokensCounted(String stopReason, Category expected) {
        response = message(stopReason, "{\\\"a\\\":");

        LlmProviderException error = failure();

        assertThat(error.category()).isEqualTo(expected);
        assertThat(error.usage()).isEqualTo(new LlmUsage(123, 45));
    }

    @Test
    void thinkingIsNotPartOfTheAnswer() {
        response = """
                {"id": "m", "type": "message", "role": "assistant", "model": "model-that-answered", "stop_reason": "end_turn",
                 "stop_sequence": null, "content": [
                   {"type": "thinking", "thinking": "let me think", "signature": "s"}, {"type": "text", "text": "answer", "citations": null}],
                 "usage": {"input_tokens": 1, "output_tokens": 2, "cache_creation_input_tokens": null, "cache_read_input_tokens": null}}""";

        assertThat(provider("").generate(REQUEST).text()).isEqualTo("answer");
    }

    @Test
    void nothingSensitiveIsPrinted() {
        assertThat(properties("", 1000).toString()).doesNotContain(KEY).contains("<set>");
        assertThat(REQUEST.toString()).doesNotContain("the user prompt").doesNotContain("the system prompt");
        assertThat(new LlmResponse("answer text", "p", "m", LlmUsage.NONE).toString()).doesNotContain("answer text");
    }

    private LlmProviderException failure() {
        return catchThrowableOfType(LlmProviderException.class, () -> provider("").generate(REQUEST));
    }

    private AnthropicLlmProvider provider(String effort) {
        return provider(effort, 30000);
    }

    private AnthropicLlmProvider provider(String effort, int timeoutMs) {
        return provider(effort, timeoutMs, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static AnthropicLlmProvider provider(String effort, int timeoutMs, String url) {
        AnthropicProperties properties = properties(effort, timeoutMs);
        return new AnthropicLlmProvider(properties, AnthropicOkHttpClient.builder().apiKey(KEY).baseUrl(url)
                .timeout(Duration.ofMillis(timeoutMs)).maxRetries(0).build());
    }

    private static AnthropicProperties properties(String effort, int timeoutMs) {
        return new AnthropicProperties(KEY, "configured-model", 1234, timeoutMs, effort);
    }

    private static String message(String stopReason, String escapedText) {
        return """
                {"id": "msg_1", "type": "message", "role": "assistant", "model": "model-that-answered",
                 "content": [{"type": "text", "text": "%s", "citations": null}], "stop_reason": "%s", "stop_sequence": null,
                 "usage": {"input_tokens": 100, "output_tokens": 45, "cache_creation_input_tokens": 20,
                           "cache_read_input_tokens": 3}}""".formatted(escapedText, stopReason);
    }
}
