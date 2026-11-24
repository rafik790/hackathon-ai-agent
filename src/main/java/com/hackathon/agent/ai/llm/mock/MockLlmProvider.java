package com.hackathon.agent.ai.llm.mock;

import com.hackathon.agent.ai.llm.LlmProvider;
import com.hackathon.agent.ai.llm.LlmRequest;
import com.hackathon.agent.ai.llm.LlmResponse;
import com.hackathon.agent.ai.llm.LlmUsage;
import java.util.function.Function;

/**
 * Provider for development and tests. It makes no external calls, and its default answer says so: it is a valid
 * analysis without any claim about the failure.
 */
public class MockLlmProvider implements LlmProvider {

    public static final String NAME = "mock";
    static final String MODEL = "mock-model";
    private static final int CHARS_PER_TOKEN = 4;

    private static final String DEFAULT_ANSWER = """
            {
              "summary": "[MOCK] Placeholder analysis; no AI model was called.",
              "failureCategory": "UNKNOWN",
              "rootCause": "[MOCK] Not determined. Configure a real provider to analyse this failure.",
              "severity": "LOW",
              "confidence": 0.0,
              "observedFacts": ["[MOCK] The evidence was read but not interpreted."],
              "hypotheses": [],
              "supportingEvidence": [],
              "recommendedActions": [
                {"priority": "LOW", "action": "[MOCK] Configure a real AI provider", "reason": "The mock provider is active"}
              ],
              "preventiveRecommendations": []
            }
            """;

    private final Function<LlmRequest, String> answers;

    public MockLlmProvider() {
        this(request -> DEFAULT_ANSWER);
    }

    /** @param answers produces the answer, or throws a provider exception, for each request */
    public MockLlmProvider(Function<LlmRequest, String> answers) {
        this.answers = answers;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        String text = answers.apply(request);
        int input = (request.systemPrompt().length() + request.userPrompt().length()) / CHARS_PER_TOKEN;
        return new LlmResponse(text, NAME, MODEL, new LlmUsage(input, text.length() / CHARS_PER_TOKEN));
    }
}
