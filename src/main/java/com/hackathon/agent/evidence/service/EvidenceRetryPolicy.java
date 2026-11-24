package com.hackathon.agent.evidence.service;

import com.hackathon.agent.config.EvidenceProperties;
import com.hackathon.agent.evidence.client.EvidenceApiException;
import com.hackathon.agent.evidence.model.EvidenceStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Decides whether a failed collection is retried and when.
 */
@Component
public class EvidenceRetryPolicy {

    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final EvidenceProperties properties;
    private final Clock clock;

    public EvidenceRetryPolicy(EvidenceProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isExhausted(int fetchAttempts) {
        return fetchAttempts >= properties.maxFetchAttempts();
    }

    /** Status after a failed request; only transient and permission errors are retried, and only a few times. */
    public EvidenceStatus statusAfter(EvidenceApiException.Category category, int fetchAttempts) {
        return switch (category) {
            case PERMANENT, NOT_FOUND -> EvidenceStatus.FAILED;
            case FORBIDDEN -> isExhausted(fetchAttempts) ? EvidenceStatus.FORBIDDEN : EvidenceStatus.PENDING;
            case TRANSIENT -> isExhausted(fetchAttempts) ? EvidenceStatus.FAILED : EvidenceStatus.PENDING;
        };
    }

    /** Exponential backoff: base, 2x, 4x, ... up to one hour; null for final states. */
    public Instant nextAttemptAt(EvidenceStatus status, int fetchAttempts) {
        if (status.isFinal()) {
            return null;
        }
        int doublings = Math.min(Math.max(fetchAttempts, 1) - 1, 16);
        Duration backoff = Duration.ofSeconds(properties.retryBackoffSeconds()).multipliedBy(1L << doublings);
        return clock.instant().plus(backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff);
    }
}
