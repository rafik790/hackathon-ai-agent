package com.hackathon.agent.ai.llm;

import java.util.Map;

/**
 * A single question to a model.
 *
 * @param systemPrompt   instructions
 * @param userPrompt     data to work on
 * @param responseSchema JSON schema of the expected answer; providers without schema support may ignore it
 */
public record LlmRequest(String systemPrompt, String userPrompt, Map<String, Object> responseSchema) {

    /** Prompts contain log excerpts and must never end up in application logs. */
    @Override
    public String toString() {
        return "LlmRequest[systemPrompt=%d chars, userPrompt=%d chars]".formatted(systemPrompt.length(), userPrompt.length());
    }
}
