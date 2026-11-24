package com.hackathon.agent.ai.llm;

/**
 * Billed tokens of one request.
 */
public record LlmUsage(int inputTokens, int outputTokens) {

    public static final LlmUsage NONE = new LlmUsage(0, 0);
}
