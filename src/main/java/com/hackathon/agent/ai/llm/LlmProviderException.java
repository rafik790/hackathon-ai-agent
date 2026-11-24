package com.hackathon.agent.ai.llm;

import java.time.Duration;
import java.util.Optional;

/**
 * A request that produced no usable answer, classified independently of the provider.
 */
public class LlmProviderException extends RuntimeException {

    public enum Category {
        /** Credentials are missing, invalid or not allowed to use the model. */
        AUTHENTICATION(false),
        /** The request itself is not acceptable; repeating it cannot help. */
        INVALID_REQUEST(false),
        /** The account has no credit or reached a spending limit. */
        QUOTA_EXCEEDED(false),
        /** The model declined to answer. */
        REFUSED(false),
        /** The answer was cut off by the configured output limit. */
        TRUNCATED(false),
        RATE_LIMITED(true),
        TIMEOUT(true),
        /** Server errors, overload and connection problems. */
        UNAVAILABLE(true),
        /** The provider answered with something that cannot be interpreted. */
        INVALID_RESPONSE(true);

        private final boolean retryable;

        Category(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }

        /** Affects every request, so the caller should stop sending more for a while. */
        public boolean affectsAllRequests() {
            return this == AUTHENTICATION || this == QUOTA_EXCEEDED || this == RATE_LIMITED;
        }
    }

    private final Category category;
    private final transient LlmUsage usage;
    private final transient Duration retryAfter;

    /** @param message must not contain credentials or prompt content; it is persisted and shown to users */
    public LlmProviderException(Category category, String message, Throwable cause) {
        this(category, message, cause, LlmUsage.NONE, null);
    }

    public LlmProviderException(Category category, String message, Throwable cause, LlmUsage usage,
                                Duration retryAfter) {
        super(message, cause);
        this.category = category;
        this.usage = usage;
        this.retryAfter = retryAfter;
    }

    public Category category() {
        return category;
    }

    /** Tokens billed although the request failed. */
    public LlmUsage usage() {
        return usage;
    }

    /** Waiting time requested by the provider. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
