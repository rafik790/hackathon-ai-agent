package com.hackathon.agent.ai.llm.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.ai.llm.LlmRequest;
import com.hackathon.agent.ai.llm.LlmResponse;
import com.hackathon.agent.ai.model.FailureAnalysisResult;
import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.ai.parser.FailureAnalysisParser;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.support.AnalysisFixtures;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MockLlmProviderTest {

    private static final LlmRequest REQUEST = new LlmRequest("12345678", "1234", Map.of());

    @Test
    void defaultAnswerIsAValidAnalysisThatClaimsNothing() {
        LlmResponse response = new MockLlmProvider().generate(REQUEST);

        FailureAnalysisResult result = new FailureAnalysisParser(new LogSanitizer())
                .parse(response.text(), AnalysisFixtures.context("log"));
        assertThat(result.failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(result.confidence()).isZero();
        assertThat(result.summary()).startsWith("[MOCK]");
        assertThat(response.provider()).isEqualTo("mock");
        assertThat(response.model()).isEqualTo("mock-model");
        assertThat(response.usage().inputTokens()).isEqualTo(3);
        assertThat(response.usage().outputTokens()).isPositive();
    }

    @Test
    void answersCanBeProvided() {
        assertThat(new MockLlmProvider(request -> request.userPrompt() + "!").generate(REQUEST).text()).isEqualTo("1234!");
    }
}
