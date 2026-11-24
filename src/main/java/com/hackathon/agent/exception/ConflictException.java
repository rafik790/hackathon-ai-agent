package com.hackathon.agent.exception;

/**
 * A valid request that conflicts with the current state. Maps to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
