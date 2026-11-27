package com.hackathon.agent.investigation.tool;

/**
 * A tool could not read what was asked for. The message is shown to the model and stored, so it must not contain
 * response bodies or credentials.
 */
public class AgentToolException extends RuntimeException {

    public AgentToolException(String message) {
        super(message);
    }

    public AgentToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
