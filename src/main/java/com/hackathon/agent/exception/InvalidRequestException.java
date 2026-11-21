package com.hackathon.agent.exception;

/**
 * A request that is syntactically valid but semantically wrong (e.g. inverted date range). Maps to HTTP 400.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
