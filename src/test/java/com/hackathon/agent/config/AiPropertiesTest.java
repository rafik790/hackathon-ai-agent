package com.hackathon.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.ai.llm.anthropic.AnthropicProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

class AiPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties({AiProperties.class, AnthropicProperties.class})
    static class Config {
    }

    @Test
    void isDisabledWithTheDocumentedDefaults() {
        runner.run(context -> {
            assertThat(context.getBean(AiProperties.class)).isEqualTo(new AiProperties(false, "anthropic",
                    new AiProperties.Analysis(1, false, 24, 100, 5, 30000, 15000, 2, 3, 60, 600, 60000)));
            assertThat(context.getBean(AnthropicProperties.class).maxTokens()).isEqualTo(8192);
        });
    }

    @Test
    void everyValueCanBeSetThroughTheEnvironment() {
        Map<String, Object> environment = Map.ofEntries(Map.entry("AI_ENABLED", "true"), Map.entry("AI_PROVIDER", "mock"),
                Map.entry("ANTHROPIC_API_KEY", "placeholder"), Map.entry("ANTHROPIC_MODEL", "m"),
                Map.entry("ANTHROPIC_MAX_TOKENS", "300"), Map.entry("ANTHROPIC_TIMEOUT_MS", "1001"),
                Map.entry("ANTHROPIC_EFFORT", "low"), Map.entry("AI_ANALYSIS_VERSION", "2"),
                Map.entry("AI_ANALYSIS_REANALYSE_ON_VERSION_CHANGE", "true"), Map.entry("AI_ANALYSIS_LOOKBACK_HOURS", "3"),
                Map.entry("AI_ANALYSIS_DAILY_LIMIT", "4"), Map.entry("AI_ANALYSIS_BATCH_SIZE", "6"),
                Map.entry("AI_ANALYSIS_POLLING_INTERVAL_MS", "1002"), Map.entry("AI_ANALYSIS_INITIAL_DELAY_MS", "7"),
                Map.entry("AI_ANALYSIS_MAX_CONCURRENT_REQUESTS", "8"), Map.entry("AI_ANALYSIS_MAX_RETRIES", "9"),
                Map.entry("AI_ANALYSIS_RETRY_BACKOFF_SECONDS", "10"), Map.entry("AI_ANALYSIS_LEASE_SECONDS", "61"),
                Map.entry("AI_ANALYSIS_MAX_LOG_CHARS", "1003"));

        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", environment))).run(context -> {
                    assertThat(context.getBean(AiProperties.class)).isEqualTo(new AiProperties(true, "mock",
                            new AiProperties.Analysis(2, true, 3, 4, 6, 1002, 7, 8, 9, 10, 61, 1003)));
                    assertThat(context.getBean(AnthropicProperties.class))
                            .isEqualTo(new AnthropicProperties("placeholder", "m", 300, 1001, "low"));
                });
    }
}
