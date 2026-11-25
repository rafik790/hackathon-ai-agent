package com.hackathon.agent.exception;

import java.time.Instant;

/**
 * A usage limit is reached. Maps to HTTP 429.
 */
public class QuotaExceededException extends RuntimeException {

    private final transient Instant availableAt;

    /** @param availableAt when the request can succeed again, or null if unknown */
    public QuotaExceededException(String message, Instant availableAt) {
        super(message);
        this.availableAt = availableAt;
    }

    public Instant availableAt() {
        return availableAt;
    }
}
