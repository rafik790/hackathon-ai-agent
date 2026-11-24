package com.hackathon.agent.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.support.AnalysisFixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AnalysisRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    private final AnalysisRetryPolicy policy =
            new AnalysisRetryPolicy(AnalysisFixtures.properties(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void allowsThreeRetriesOfRetryableErrors() {
        assertThat(policy.mayRetry(true, 1)).isTrue();
        assertThat(policy.mayRetry(true, 3)).isTrue();
        assertThat(policy.mayRetry(true, 4)).isFalse();
        assertThat(policy.mayRetry(false, 1)).isFalse();
    }

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(delay(1, null)).isEqualTo(Duration.ofMinutes(1));
        assertThat(delay(2, null)).isEqualTo(Duration.ofMinutes(2));
        assertThat(delay(3, null)).isEqualTo(Duration.ofMinutes(4));
        assertThat(delay(15, null)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void respectsALongerWaitingTimeOfTheProvider() {
        assertThat(delay(1, Duration.ofMinutes(10))).isEqualTo(Duration.ofMinutes(10));
        assertThat(delay(1, Duration.ofSeconds(1))).isEqualTo(Duration.ofMinutes(1));
    }

    private Duration delay(int attempts, Duration retryAfter) {
        return Duration.between(NOW, policy.nextAttemptAt(attempts, retryAfter));
    }
}
