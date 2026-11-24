package com.hackathon.agent.ai.service;

import com.hackathon.agent.config.AiProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Exponential backoff between analysis attempts.
 */
@Component
public class AnalysisRetryPolicy {

    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final AiProperties properties;
    private final Clock clock;

    public AnalysisRetryPolicy(AiProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean mayRetry(boolean retryable, int attempts) {
        return retryable && attempts < properties.analysis().maxAttempts();
    }

    /** @param retryAfter waiting time requested by the provider, or null */
    public Instant nextAttemptAt(int attempts, Duration retryAfter) {
        int doublings = Math.min(Math.max(attempts, 1) - 1, 16);
        Duration backoff = Duration.ofSeconds(properties.analysis().retryBackoffSeconds()).multipliedBy(1L << doublings);
        if (backoff.compareTo(MAX_BACKOFF) > 0) {
            backoff = MAX_BACKOFF;
        }
        if (retryAfter != null && retryAfter.compareTo(backoff) > 0) {
            backoff = retryAfter;
        }
        return clock.instant().plus(backoff);
    }
}
