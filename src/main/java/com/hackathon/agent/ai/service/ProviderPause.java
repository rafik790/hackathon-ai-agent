package com.hackathon.agent.ai.service;

import com.hackathon.agent.ai.llm.LlmProviderException;
import com.hackathon.agent.ai.llm.LlmProviderException.Category;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stops all requests for a while after an error that would affect every request, e.g. a rate limit or a wrong key.
 */
@Component
public class ProviderPause {

    private static final Logger log = LoggerFactory.getLogger(ProviderPause.class);

    private static final Duration RATE_LIMIT_PAUSE = Duration.ofMinutes(1);
    private static final Duration ACCOUNT_PAUSE = Duration.ofMinutes(15);

    private final Clock clock;
    private Instant pausedUntil = Instant.MIN;

    public ProviderPause(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean isActive() {
        return clock.instant().isBefore(pausedUntil);
    }

    public synchronized void startIfNeeded(LlmProviderException error) {
        if (!error.category().affectsAllRequests()) {
            return;
        }
        Instant until = clock.instant().plus(durationAfter(error));
        if (until.isAfter(pausedUntil)) {
            pausedUntil = until;
        }
        log.warn("Analysis paused until {} after {}", pausedUntil, error.category());
    }

    /** The default pause, or longer if the provider asks for it. */
    private static Duration durationAfter(LlmProviderException error) {
        Duration pause = error.category() == Category.RATE_LIMITED ? RATE_LIMIT_PAUSE : ACCOUNT_PAUSE;
        return error.retryAfter().filter(requested -> requested.compareTo(pause) > 0).orElse(pause);
    }
}
