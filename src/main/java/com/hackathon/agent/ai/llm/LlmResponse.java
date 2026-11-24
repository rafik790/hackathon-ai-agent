package com.hackathon.agent.ai.llm;

/**
 * A complete answer.
 *
 * @param model model that actually answered
 */
public record LlmResponse(String text, String provider, String model, LlmUsage usage) {

    @Override
    public String toString() {
        return "LlmResponse[text=%d chars, provider=%s, model=%s, usage=%s]".formatted(text.length(), provider, model, usage);
    }
}
