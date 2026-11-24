package com.hackathon.agent.ai.llm.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.hackathon.agent.ai.llm.LlmProvider;
import com.hackathon.agent.ai.llm.LlmProviderException;
import com.hackathon.agent.ai.llm.LlmProviderException.Category;
import com.hackathon.agent.ai.llm.LlmRequest;
import com.hackathon.agent.ai.llm.LlmResponse;
import com.hackathon.agent.ai.llm.LlmUsage;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Anthropic Claude through the official Java SDK. All SDK types stay inside this class.
 * <p>
 * SDK retries are switched off because the caller owns retries. The answer is requested as structured output, so
 * the model must follow the JSON schema. Error messages never contain the API key, the prompt or response bodies.
 */
public class AnthropicLlmProvider implements LlmProvider, AutoCloseable {

    public static final String NAME = "anthropic";

    private final AnthropicClient client;
    private final AnthropicProperties properties;

    public AnthropicLlmProvider(AnthropicProperties properties) {
        this(validated(properties), AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey().trim())
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .maxRetries(0)
                .build());
    }

    AnthropicLlmProvider(AnthropicProperties properties, AnthropicClient client) {
        this.properties = properties;
        this.client = client;
    }

    private static AnthropicProperties validated(AnthropicProperties properties) {
        requireSet(properties.apiKey(), "ai.anthropic.api-key", "ANTHROPIC_API_KEY");
        requireSet(properties.model(), "ai.anthropic.model", "ANTHROPIC_MODEL");
        return properties;
    }

    private static void requireSet(String value, String property, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("AI provider 'anthropic' requires %s (environment variable %s)".formatted(property, variable));
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        Message message;
        try {
            message = client.messages().create(toParams(request));
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw failure(Category.AUTHENTICATION, e, null);
        } catch (RateLimitException e) {
            // A spend limit also answers 429 but tells clients not to retry.
            throw failure(isRetryForbidden(e) ? Category.QUOTA_EXCEEDED : Category.RATE_LIMITED, e, retryAfter(e));
        } catch (AnthropicServiceException e) {
            throw failure(categoryOf(e), e, retryAfter(e));
        } catch (AnthropicIoException e) {
            boolean timeout = hasCause(e, InterruptedIOException.class);
            throw new LlmProviderException(timeout ? Category.TIMEOUT : Category.UNAVAILABLE,
                    timeout ? "Anthropic request timed out" : "Anthropic could not be reached", e);
        } catch (AnthropicException e) {
            throw new LlmProviderException(Category.INVALID_RESPONSE, "Anthropic returned an unreadable response", e);
        }
        return toResponse(message);
    }

    private MessageCreateParams toParams(LlmRequest request) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(properties.model().trim())
                .maxTokens(properties.maxTokens())
                .system(request.systemPrompt())
                .addUserMessage(request.userPrompt());
        boolean hasEffort = properties.effort() != null && !properties.effort().isBlank();
        boolean hasSchema = request.responseSchema() != null && !request.responseSchema().isEmpty();
        if (hasEffort || hasSchema) {
            OutputConfig.Builder output = OutputConfig.builder();
            if (hasEffort) {
                output.effort(OutputConfig.Effort.of(properties.effort().trim().toLowerCase(Locale.ROOT)));
            }
            if (hasSchema) {
                output.format(JsonOutputFormat.builder().schema(schema(request.responseSchema())).build());
            }
            params.outputConfig(output.build());
        }
        return params.build();
    }

    private static JsonOutputFormat.Schema schema(Map<String, Object> schema) {
        JsonOutputFormat.Schema.Builder builder = JsonOutputFormat.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return builder.build();
    }

    private LlmResponse toResponse(Message message) {
        LlmUsage usage;
        StopReason stopReason;
        String text;
        String model;
        try {
            // Accessors of the SDK throw when mandatory fields are missing.
            usage = new LlmUsage(tokens(message.usage().inputTokens()
                    + message.usage().cacheCreationInputTokens().orElse(0L)
                    + message.usage().cacheReadInputTokens().orElse(0L)), tokens(message.usage().outputTokens()));
            stopReason = message.stopReason().orElse(null);
            text = message.content().stream().flatMap(block -> block.text().stream())
                    .map(block -> block.text()).collect(Collectors.joining());
            model = message.model().asString();
        } catch (AnthropicException e) {
            throw new LlmProviderException(Category.INVALID_RESPONSE, "Anthropic returned an unexpected response", e);
        }
        if (StopReason.REFUSAL.equals(stopReason)) {
            throw new LlmProviderException(Category.REFUSED, "The model declined to analyse this failure", null, usage, null);
        }
        if (StopReason.MAX_TOKENS.equals(stopReason)) {
            throw new LlmProviderException(Category.TRUNCATED,
                    "The answer exceeded the output limit of %d tokens (ANTHROPIC_MAX_TOKENS)".formatted(properties.maxTokens()),
                    null, usage, null);
        }
        if (StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED.equals(stopReason)) {
            throw new LlmProviderException(Category.TRUNCATED, "The analysis exceeded the context window of the model",
                    null, usage, null);
        }
        if (text.isBlank()) {
            throw new LlmProviderException(Category.INVALID_RESPONSE, "Anthropic returned no text", null, usage, null);
        }
        return new LlmResponse(text, NAME, model, usage);
    }

    private static Category categoryOf(AnthropicServiceException e) {
        int status = e.statusCode();
        if (status == 402) {
            return Category.QUOTA_EXCEEDED;
        }
        if (status == 408) {
            return Category.TIMEOUT;
        }
        if (status >= 500) {
            return Category.UNAVAILABLE;
        }
        return Category.INVALID_REQUEST;
    }

    private static LlmProviderException failure(Category category, AnthropicServiceException e, Duration retryAfter) {
        String type = e.errorType().map(t -> " (" + t.asString() + ")").orElse("");
        return new LlmProviderException(category, "Anthropic request failed: HTTP " + e.statusCode() + type, e,
                LlmUsage.NONE, retryAfter);
    }

    private static boolean isRetryForbidden(AnthropicServiceException e) {
        return e.headers().values("x-should-retry").stream().anyMatch("false"::equalsIgnoreCase);
    }

    private static Duration retryAfter(AnthropicServiceException e) {
        return e.headers().values("retry-after").stream().findFirst().map(value -> {
            try {
                return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }).orElse(null);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static int tokens(long value) {
        return (int) Math.clamp(value, 0, Integer.MAX_VALUE);
    }

    @Override
    public void close() {
        client.close();
    }
}
