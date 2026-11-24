package com.hackathon.agent.ai.llm;

import com.hackathon.agent.ai.llm.anthropic.AnthropicLlmProvider;
import com.hackathon.agent.ai.llm.anthropic.AnthropicProperties;
import com.hackathon.agent.ai.llm.mock.MockLlmProvider;
import com.hackathon.agent.config.AiProperties;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the configured provider; the only place that knows which providers exist. No provider is created, and
 * no credentials are needed, while AI analysis is disabled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "ai.enabled")
public class LlmProviderConfig {

    @Bean
    public LlmProvider llmProvider(AiProperties properties, AnthropicProperties anthropic) {
        return switch (properties.provider().trim().toLowerCase(Locale.ROOT)) {
            case AnthropicLlmProvider.NAME -> new AnthropicLlmProvider(anthropic);
            case MockLlmProvider.NAME -> new MockLlmProvider();
            default -> throw new IllegalStateException("Unsupported AI provider '%s' (ai.provider / AI_PROVIDER). Supported providers: %s, %s"
                    .formatted(properties.provider(), AnthropicLlmProvider.NAME, MockLlmProvider.NAME));
        };
    }
}
