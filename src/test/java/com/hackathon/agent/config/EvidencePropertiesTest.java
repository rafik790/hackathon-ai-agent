package com.hackathon.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

class EvidencePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(EvidenceProperties.class)
    static class Config {
    }

    @Test
    void isDisabledWithTheDocumentedDefaults() {
        runner.run(context -> assertThat(context.getBean(EvidenceProperties.class)).isEqualTo(
                new EvidenceProperties(false, 30000, 20000, 24, 10, 2, 500, 65536, 262144, 14, 5, 60, 10000)));
    }

    @Test
    void everyValueCanBeSetThroughTheEnvironment() {
        Map<String, Object> environment = Map.ofEntries(Map.entry("EVIDENCE_ENABLED", "true"),
                Map.entry("EVIDENCE_POLLING_INTERVAL_MS", "1001"), Map.entry("EVIDENCE_INITIAL_DELAY_MS", "2"),
                Map.entry("EVIDENCE_LOOKBACK_HOURS", "3"), Map.entry("EVIDENCE_BATCH_SIZE", "4"),
                Map.entry("EVIDENCE_MAX_CONCURRENT_LOG_REQUESTS", "5"), Map.entry("EVIDENCE_MAX_LOG_LINES", "6"),
                Map.entry("EVIDENCE_MAX_CONTAINER_LOG_BYTES", "1025"),
                Map.entry("EVIDENCE_MAX_TASKRUN_LOG_BYTES", "1026"), Map.entry("EVIDENCE_RETENTION_DAYS", "7"),
                Map.entry("EVIDENCE_MAX_FETCH_ATTEMPTS", "8"), Map.entry("EVIDENCE_RETRY_BACKOFF_SECONDS", "9"),
                Map.entry("EVIDENCE_REQUEST_TIMEOUT_MS", "110"));

        runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", environment)))
                .run(context -> assertThat(context.getBean(EvidenceProperties.class)).isEqualTo(
                        new EvidenceProperties(true, 1001, 2, 3, 4, 5, 6, 1025, 1026, 7, 8, 9, 110)));
    }

    @Test
    void logLimitCannotExceedTheColumnSize() {
        runner.withPropertyValues("evidence.max-container-log-bytes=65537")
                .run(context -> assertThat(context).hasFailed());
    }
}
