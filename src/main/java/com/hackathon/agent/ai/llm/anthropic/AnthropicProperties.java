package com.hackathon.agent.ai.llm.anthropic;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Anthropic settings ({@code ai.anthropic.*}). API key and model have no defaults and are only required when this
 * provider is active.
 *
 * @param maxTokens output limit per request; includes the thinking of models that always think
 * @param timeoutMs timeout of one request
 * @param effort    optional effort level (e.g. low, medium, high); blank uses the default of the model
 */
@Validated
@ConfigurationProperties(prefix = "ai.anthropic")
public record AnthropicProperties(
        String apiKey,
        String model,
        @DefaultValue("8192") @Min(256) int maxTokens,
        @DefaultValue("120000") @Min(1000) int timeoutMs,
        String effort) {

    @Override
    public String toString() {
        return "AnthropicProperties[apiKey=%s, model=%s, maxTokens=%d, timeoutMs=%d, effort=%s]".formatted(
                apiKey == null || apiKey.isBlank() ? "<not set>" : "<set>", model, maxTokens, timeoutMs, effort);
    }
}
