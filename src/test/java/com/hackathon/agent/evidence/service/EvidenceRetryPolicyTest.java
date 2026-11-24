package com.hackathon.agent.evidence.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.evidence.client.EvidenceApiException.Category;
import com.hackathon.agent.evidence.model.EvidenceStatus;
import com.hackathon.agent.support.EvidenceFixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class EvidenceRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private final EvidenceRetryPolicy policy =
            new EvidenceRetryPolicy(EvidenceFixtures.properties(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void transientErrorsAreRetriedUpToTheLimit() {
        assertThat(policy.statusAfter(Category.TRANSIENT, 1)).isEqualTo(EvidenceStatus.PENDING);
        assertThat(policy.statusAfter(Category.TRANSIENT, 4)).isEqualTo(EvidenceStatus.PENDING);
        assertThat(policy.statusAfter(Category.TRANSIENT, 5)).isEqualTo(EvidenceStatus.FAILED);
    }

    @Test
    void permissionErrorsAreRetriedUpToTheLimit() {
        assertThat(policy.statusAfter(Category.FORBIDDEN, 1)).isEqualTo(EvidenceStatus.PENDING);
        assertThat(policy.statusAfter(Category.FORBIDDEN, 5)).isEqualTo(EvidenceStatus.FORBIDDEN);
    }

    @Test
    void permanentErrorsAreNotRetried() {
        assertThat(policy.statusAfter(Category.PERMANENT, 1)).isEqualTo(EvidenceStatus.FAILED);
        assertThat(policy.statusAfter(Category.NOT_FOUND, 1)).isEqualTo(EvidenceStatus.FAILED);
    }

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(delay(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(delay(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(delay(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(delay(19)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void finalStatesHaveNoNextAttempt() {
        assertThat(policy.nextAttemptAt(EvidenceStatus.COLLECTED, 1)).isNull();
    }

    private Duration delay(int fetchAttempts) {
        return Duration.between(NOW, policy.nextAttemptAt(EvidenceStatus.PENDING, fetchAttempts));
    }
}
