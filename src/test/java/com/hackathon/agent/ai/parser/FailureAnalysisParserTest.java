package com.hackathon.agent.ai.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hackathon.agent.ai.model.EvidenceSource;
import com.hackathon.agent.ai.model.FailureAnalysisResult;
import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.ai.model.RecommendationPriority;
import com.hackathon.agent.ai.model.RecommendedAction;
import com.hackathon.agent.ai.model.Severity;
import com.hackathon.agent.ai.model.SupportingEvidence;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.support.AnalysisFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class FailureAnalysisParserTest {

    private static final String VALID = AnalysisFixtures.VALID_ANSWER;

    private final FailureAnalysisParser parser = new FailureAnalysisParser(new LogSanitizer());

    @Test
    void parsesAValidAnswer() {
        FailureAnalysisResult result = parse(VALID);

        assertThat(result.summary()).isEqualTo("Pushing the image failed.");
        assertThat(result.failureCategory()).isEqualTo(FailureCategory.REGISTRY_AUTHENTICATION);
        assertThat(result.severity()).isEqualTo(Severity.HIGH);
        assertThat(result.confidence()).isEqualByComparingTo("0.92");
        assertThat(result.observedFacts()).containsExactly("step-test exited with code 2");
        assertThat(result.hypotheses()).containsExactly("The credential may have expired");
        assertThat(result.supportingEvidence()).containsExactly(
                new SupportingEvidence(EvidenceSource.CONTAINER_STATUS, "step-test", "exitCode 2"),
                new SupportingEvidence(EvidenceSource.TASK_RUN, null, "denied: authentication required"));
        assertThat(result.recommendedActions()).containsExactly(
                new RecommendedAction(RecommendationPriority.HIGH, "Renew the credential", "It was rejected"));
        assertThat(result.preventiveRecommendations()).containsExactly("Check credentials before the build");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not json", "[]", "null", "\"text\"", "{", "{} trailing", "{\"summary\": }"})
    void rejectsAnythingButOneJsonObject(String answer) {
        assertThatThrownBy(() -> parse(answer)).isInstanceOf(InvalidAnalysisException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> parse(null)).isInstanceOf(InvalidAnalysisException.class);
    }

    @Test
    void acceptsAMarkdownCodeFence() {
        assertThat(parse("```json\n" + VALID + "\n```").severity()).isEqualTo(Severity.HIGH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"summary", "failureCategory", "rootCause", "severity", "confidence", "observedFacts",
            "hypotheses", "supportingEvidence", "recommendedActions", "preventiveRecommendations"})
    void rejectsMissingFields(String field) {
        assertThatThrownBy(() -> parse(VALID.replace("\"" + field + "\"", "\"other\"")))
                .isInstanceOf(InvalidAnalysisException.class).hasMessageContaining(field);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"HIGH\",            | \"EXTREME\",        | severity",
            "\"REGISTRY_AUTHENTICATION\" | \"SOMETHING_ELSE\" | failureCategory",
            "\"REGISTRY_AUTHENTICATION\" | 7                | failureCategory",
            "\"CONTAINER_STATUS\" | \"RUMOUR\"          | supportingEvidence[0].source",
            "{\"priority\": \"HIGH\" | {\"priority\": \"NOW\" | recommendedActions[0].priority",
            "0.92                 | 1.2                 | confidence",
            "0.92                 | -0.1                | confidence",
            "0.92                 | \"high\"            | confidence",
            "\"Pushing the image failed.\" | \"  \"     | summary",
            "\"Renew the credential\" | null            | recommendedActions[0].action",
            "[\"step-test exited with code 2\"] | []    | observedFacts",
            "[\"step-test exited with code 2\"] | [1]   | observedFacts",
            "[\"step-test exited with code 2\"] | \"x\" | observedFacts"})
    void rejectsInvalidValues(String original, String replacement, String field) {
        assertThat(VALID).contains(original);
        assertThatThrownBy(() -> parse(VALID.replace(original, replacement)))
                .isInstanceOf(InvalidAnalysisException.class).hasMessageContaining(field);
    }

    @Test
    void errorsNeverRepeatTheAnswer() {
        assertThatThrownBy(() -> parse(VALID.replace("\"HIGH\",", "\"leaked-value\",")))
                .isInstanceOf(InvalidAnalysisException.class).message().doesNotContain("leaked-value");
    }

    @ParameterizedTest
    @EnumSource(FailureCategory.class)
    void mapsEveryCategory(FailureCategory category) {
        assertThat(parse(VALID.replace("REGISTRY_AUTHENTICATION", category.name())).failureCategory()).isEqualTo(category);
    }

    @ParameterizedTest
    @ValueSource(strings = {"registry_authentication", "Registry Authentication", "registry-authentication", " REGISTRY_AUTHENTICATION "})
    void toleratesSpellingVariantsOfCategories(String spelling) {
        assertThat(parse(VALID.replace("REGISTRY_AUTHENTICATION", spelling)).failureCategory())
                .isEqualTo(FailureCategory.REGISTRY_AUTHENTICATION);
    }

    @Test
    void sanitizesAndBoundsText() {
        FailureAnalysisResult result = parse(VALID
                .replace("Pushing the image failed.", "x".repeat(5000))
                .replace("The registry rejected the credentials.", "Used password=hunter2 for login"));

        assertThat(result.summary()).hasSize(FailureAnalysisParser.MAX_SUMMARY);
        assertThat(result.rootCause()).isEqualTo("Used password=[REDACTED] for login");
    }

    @Test
    void boundsLists() {
        assertThat(parse(VALID.replace("[\"The credential may have expired\"]",
                "[" + "\"a\",".repeat(50) + "\"a\"]")).hypotheses()).hasSize(FailureAnalysisParser.MAX_ITEMS);
    }

    private FailureAnalysisResult parse(String answer) {
        return parser.parse(answer);
    }
}
