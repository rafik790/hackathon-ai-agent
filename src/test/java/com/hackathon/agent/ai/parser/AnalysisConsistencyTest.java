package com.hackathon.agent.ai.parser;

import static com.hackathon.agent.ai.model.EvidenceSource.CONTAINER_LOG;
import static com.hackathon.agent.ai.model.EvidenceSource.CONTAINER_STATUS;
import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.ai.llm.LlmRequest;
import com.hackathon.agent.ai.llm.mock.MockLlmProvider;
import com.hackathon.agent.ai.model.FailureAnalysisResult;
import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.ai.model.FailureContext;
import com.hackathon.agent.ai.model.RecommendationPriority;
import com.hackathon.agent.ai.model.RecommendedAction;
import com.hackathon.agent.ai.model.Severity;
import com.hackathon.agent.ai.model.SupportingEvidence;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.evidence.model.ContainerRunState;
import com.hackathon.agent.evidence.model.ContainerType;
import com.hackathon.agent.evidence.model.EvidenceStatus;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import com.hackathon.agent.evidence.model.LogStatus;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Summary, root cause, category, confidence and actions of one analysis must not contradict each other. */
class AnalysisConsistencyTest {

    private static final String TASK_RUN = "demo-app-build-bb7hj-build-and-push";
    private static final String STEP = "step-build-and-push";
    private static final String ERROR_LINE = "DemoController.java:16: error: unclosed string literal";
    private static final String COMPILER_LOG = """
            DemoController.java:16: error: unclosed string literal
            System.out.println("I get fired);
            > Task :compileJava FAILED
            """;
    private static final String DENIED = "denied: requested access to the resource is denied";
    private static final String GENERIC_SUMMARY = "Insufficient evidence to determine why TaskRun " + TASK_RUN + " failed.";
    private static final String GENERIC_CAUSE = "Insufficient evidence: the collected evidence does not confirm a root cause.";
    private static final String CAUSE = "The string literal in DemoController.java:16 has no closing quote.";
    private static final SupportingEvidence COMPILER_ERROR = new SupportingEvidence(CONTAINER_LOG, STEP, ERROR_LINE);
    private static final SupportingEvidence EXIT_CODE = new SupportingEvidence(CONTAINER_STATUS, STEP, "exitCode 1");
    private static final RecommendedAction FIX_QUOTE = new RecommendedAction(RecommendationPriority.HIGH,
            "Add the missing closing quote in DemoController.java line 16", "The string literal is not closed");

    private final AnalysisEvidenceValidator validator = new AnalysisEvidenceValidator();

    // ------------------------------------------------------------ compiler errors

    /** The analysis that was reported: correct evidence and action, but a generic conclusion. */
    @Test
    void genericAnswerToACompilerErrorIsCorrected() {
        FailureAnalysisResult validated = validate(COMPILER_LOG,
                answer(GENERIC_SUMMARY, FailureCategory.UNKNOWN, GENERIC_CAUSE, "0.3", FIX_QUOTE, COMPILER_ERROR));

        assertCompilationFailure(validated);
        assertThat(validated.confidence()).isEqualByComparingTo("0.8");
        assertThat(validated.supportingEvidence()).containsExactly(COMPILER_ERROR);
        assertThat(validated.recommendedActions()).hasSize(3).startsWith(FIX_QUOTE);
    }

    /** Wording that cannot be verified must not hide the verified compiler error. */
    @ParameterizedTest
    @ValueSource(strings = {"compileJava failed with a NullPointerException.",
            "The log shows \"connection reset by peer\" while compiling.", "step-deploy broke DemoController.java:16."})
    void inventedConclusionIsReplacedByTheVerifiedCompilerError(String conclusion) {
        FailureAnalysisResult validated = validate(COMPILER_LOG,
                answer(conclusion, FailureCategory.NETWORK, conclusion, "0.97", FIX_QUOTE, COMPILER_ERROR));

        assertCompilationFailure(validated);
        assertThat(validated.confidence()).isEqualByComparingTo("0.8");
        assertThat(validated.toString()).doesNotContain("NullPointerException").doesNotContain("connection reset")
                .doesNotContain("step-deploy");
    }

    @Test
    void inventedSummaryDoesNotHideAVerifiedRootCause() {
        FailureAnalysisResult validated = validate(COMPILER_LOG,
                answer("The step-deploy container failed.", FailureCategory.COMPILATION, CAUSE, "0.95", FIX_QUOTE, COMPILER_ERROR));

        assertCompilationFailure(validated);
    }

    @Test
    void compilerErrorTheModelIgnoredIsUsed() {
        FailureAnalysisResult validated = validate(COMPILER_LOG,
                answer(GENERIC_SUMMARY, FailureCategory.UNKNOWN, GENERIC_CAUSE, "0.2", action("Inspect the logs"), EXIT_CODE));

        assertCompilationFailure(validated);
        assertThat(validated.supportingEvidence()).containsExactly(COMPILER_ERROR, EXIT_CODE);
        assertThat(validated.recommendedActions().getFirst().action()).contains("DemoController.java, line 16");
        assertThat(validated.recommendedActions().getFirst().priority()).isEqualTo(RecommendationPriority.HIGH);
    }

    @Test
    void genericSummaryOfAConfirmedCauseIsReplaced() {
        FailureAnalysisResult validated = validate(COMPILER_LOG,
                answer(GENERIC_SUMMARY, FailureCategory.COMPILATION, CAUSE, "0.95", FIX_QUOTE, COMPILER_ERROR));

        assertThat(validated.summary()).contains("Compilation failed in DemoController.java:16").contains("missing closing quote");
        assertThat(validated.rootCause()).isEqualTo(CAUSE);
        assertThat(validated.failureCategory()).isEqualTo(FailureCategory.COMPILATION);
        assertThat(validated.confidence()).isEqualByComparingTo("0.95");
    }

    @ParameterizedTest
    @CsvSource({"UNKNOWN, 0.9, 0.9", "UNKNOWN, 0.2, 0.8", "COMPILATION, 0.2, 0.8", "COMPILATION, 0.97, 0.97"})
    void categoryAndConfidenceMatchAConfirmedCompilerError(FailureCategory category, String confidence, String expected) {
        FailureAnalysisResult validated = validate(COMPILER_LOG, answer("Compilation failed: missing quote in DemoController.java.",
                category, CAUSE, confidence, FIX_QUOTE, COMPILER_ERROR));

        assertThat(validated.failureCategory()).isEqualTo(FailureCategory.COMPILATION);
        assertThat(validated.confidence()).isEqualByComparingTo(expected);
        assertThat(validated.rootCause()).isEqualTo(CAUSE);
        assertThat(validated.summary()).isEqualTo("Compilation failed: missing quote in DemoController.java.");
    }

    @Test
    void specificCategoryOfTheModelIsNotOverruled() {
        FailureAnalysisResult answer = answer("The image build failed.", FailureCategory.IMAGE_BUILD,
                "Building the image stopped at DemoController.java:16: unclosed string literal.", "0.9", FIX_QUOTE, COMPILER_ERROR);

        assertThat(validate(COMPILER_LOG, answer)).usingRecursiveComparison().ignoringFields("recommendedActions").isEqualTo(answer);
    }

    @Test
    void rejectedStatementsStillLimitTheConfidence() {
        FailureAnalysisResult validated = validate(COMPILER_LOG, answer("Compilation failed in DemoController.java.",
                FailureCategory.COMPILATION, "DemoController.java:16 has an unclosed string literal.", "0.95", FIX_QUOTE, COMPILER_ERROR,
                new SupportingEvidence(CONTAINER_LOG, STEP, "an invented line")));

        assertThat(validated.supportingEvidence()).containsExactly(COMPILER_ERROR);
        assertThat(validated.confidence()).isEqualByComparingTo("0.6");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '^', value = {
            "/app/src/main/java/com/acme/A.java:7: error: ';' expected~1 error | A.java:7 | ';' expected",
            "src/main.c:12:5: error: unknown type name 'foo'~1 error | main.c:12 | unknown type name 'foo'",
            "e: file:///app/src/Main.kt:3:9 Unresolved reference 'x'.~> Task :compileKotlin FAILED | Main.kt:3 | Unresolved reference 'x'",
            "[ERROR] /app/src/A.java:[16,28] unclosed string literal~[ERROR] COMPILATION ERROR : | A.java:16 | unclosed string literal",
            "src/app.ts(3,5): error TS1002: Unterminated string literal.~Found 1 error. | app.ts:3 | Unterminated string literal"})
    void recognizesCommonCompilers(String log, String location, String message) {
        FailureAnalysisResult validated = validate(log.replace('~', '\n'),
                answer(GENERIC_SUMMARY, FailureCategory.UNKNOWN, GENERIC_CAUSE, "0.3", action("Inspect")));

        assertThat(validated.failureCategory()).isEqualTo(FailureCategory.COMPILATION);
        assertThat(validated.summary()).contains(location).contains(message);
    }

    // ------------------------------------------------------------ no compiler error

    @Test
    void onlyAnExitCodeIsStillInsufficientEvidence() {
        FailureAnalysisResult validated = validate("step started\nexit status 1\n", answer("The build failed.",
                FailureCategory.COMPILATION, "The code does not compile.", "0.9", action("Fix the code"), EXIT_CODE));

        assertThat(validated.summary()).isEqualTo(GENERIC_SUMMARY);
        assertThat(validated.rootCause()).isEqualTo(GENERIC_CAUSE);
        assertThat(validated.failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(validated.confidence()).isEqualByComparingTo("0.3");
        assertThat(validated.recommendedActions().getFirst().action()).startsWith("Inspect the TaskRun");
    }

    @Test
    void honestInsufficientAnswerForAnExitCodeIsKept() {
        FailureAnalysisResult answer = answer("The cause cannot be determined from the exit code.", FailureCategory.UNKNOWN,
                "Insufficient evidence: the log contains no diagnostic.", "0.2", action("Run the build again with more output"), EXIT_CODE);

        assertThat(validate("exit status 1", answer)).isEqualTo(answer);
    }

    @Test
    void guessDoesNotBecomeTheSummary() {
        FailureAnalysisResult answer = answer(GENERIC_SUMMARY, FailureCategory.UNKNOWN, "Perhaps the step ran out of memory.", "0.2",
                action("Check the memory"), EXIT_CODE);

        assertThat(validate("exit status 1", answer)).isEqualTo(answer);
    }

    /** Verified, but not a compiler error: nothing is concluded from it, but it is not hidden either. */
    @Test
    void summaryNamesVerifiedEvidenceOfAnUnconfirmedCause() {
        FailureAnalysisResult validated = validate(DENIED, answer("The network failed.", FailureCategory.NETWORK,
                "The host 10.9.8.7 was unreachable.", "0.9", action("Check"), new SupportingEvidence(CONTAINER_LOG, STEP, DENIED)));

        assertThat(validated.summary()).contains(DENIED).contains(STEP).doesNotContain("Insufficient evidence");
        assertThat(validated.failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(validated.confidence()).isEqualByComparingTo("0.3");
        assertThat(validated.toString()).doesNotContain("10.9.8.7");
    }

    @Test
    void genericSummaryOfAnotherConfirmedCauseIsReplacedByTheCause() {
        String cause = "The registry denied access to the resource.";
        FailureAnalysisResult validated = validate(DENIED, answer(GENERIC_SUMMARY, FailureCategory.REGISTRY_AUTHENTICATION, cause, "0.9",
                action("Check"), new SupportingEvidence(CONTAINER_LOG, STEP, DENIED)));

        assertThat(validated.summary()).isEqualTo(cause);
        assertThat(validated.failureCategory()).isEqualTo(FailureCategory.REGISTRY_AUTHENTICATION);
        assertThat(validated.confidence()).isEqualByComparingTo("0.9");
    }

    @Test
    void insufficientRootCauseDoesNotGoWithACategoryAndHighConfidence() {
        String cause = "Insufficient evidence: the beginning of the log is missing.";
        FailureAnalysisResult validated = validate(DENIED, answer("The push was denied.", FailureCategory.REGISTRY_AUTHENTICATION, cause,
                "0.9", action("Check"), new SupportingEvidence(CONTAINER_LOG, STEP, DENIED)));

        assertThat(validated.failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(validated.confidence()).isEqualByComparingTo("0.3");
        assertThat(validated.rootCause()).isEqualTo(cause);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DemoController.java:16: warning: unchecked\n1 warning\n> Task :compileJava",
            "DemoController.java:16: error: unclosed string literal",
            "DemoController.java:16: error: unclosed string literal\nFound 0 errors\n0 errors",
            "See DemoController.java for an error\n> Task :compileJava FAILED"})
    void textThatDoesNotProveACompilationFailureIsNotUsed(String log) {
        FailureAnalysisResult validated = validate(log,
                answer(GENERIC_SUMMARY, FailureCategory.UNKNOWN, GENERIC_CAUSE, "0.3", action("Inspect"), EXIT_CODE));

        assertThat(validated.failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(validated.confidence()).isEqualByComparingTo("0.3");
    }

    @Test
    void compilerErrorOfAContainerThatDidNotFailIsNotUsed() {
        FailureAnalysisResult answer = answer(GENERIC_SUMMARY, FailureCategory.UNKNOWN, GENERIC_CAUSE, "0.3", action("Inspect"));

        assertThat(validator.validate(answer, context(COMPILER_LOG, 0, false)).failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
    }

    @Test
    void mockAnswerIsNotTurnedIntoAnAnalysis() {
        FailureAnalysisResult mock = new FailureAnalysisParser(new LogSanitizer())
                .parse(new MockLlmProvider().generate(new LlmRequest("", "", Map.of())).text());

        assertThat(validate(COMPILER_LOG, mock).summary()).startsWith("[MOCK]");
    }

    // ------------------------------------------------------------ helpers

    private static void assertCompilationFailure(FailureAnalysisResult result) {
        assertThat(result.summary()).contains("Compilation failed").contains("DemoController.java:16")
                .contains("unclosed string literal").contains("missing closing quote").hasSizeLessThan(200);
        assertThat(result.rootCause()).contains("DemoController.java:16");
        assertThat(result.failureCategory()).isEqualTo(FailureCategory.COMPILATION);
        assertThat(result.toString()).doesNotContainIgnoringCase("insufficient evidence");
    }

    private FailureAnalysisResult validate(String log, FailureAnalysisResult answer) {
        return validator.validate(answer, context(log, 1, true));
    }

    private static RecommendedAction action(String text) {
        return new RecommendedAction(RecommendationPriority.MEDIUM, text, "It may help");
    }

    private static FailureAnalysisResult answer(String summary, FailureCategory category, String rootCause, String confidence,
                                                RecommendedAction action, SupportingEvidence... evidence) {
        return new FailureAnalysisResult(summary, category, rootCause, Severity.MEDIUM, new BigDecimal(confidence).setScale(3),
                List.of("TaskRun " + TASK_RUN + " has status FAILED"), List.of(), List.of(evidence), List.of(action), List.of());
    }

    private static FailureContext context(String log, int exitCode, boolean primary) {
        return new FailureContext(null,
                new FailureContext.TaskRun(TASK_RUN, "t-uid", "build-and-push", "hackathon", ExecutionStatus.FAILED, null, null,
                        "Failed", "\"step-build-and-push\" exited with code 1", 0, 1),
                EvidenceStatus.COLLECTED,
                new FailureContext.Pod(TASK_RUN + "-pod", "p-uid", "Failed", null, null),
                List.of(new FailureContext.Container(STEP, ContainerType.STEP, "build-and-push", 0, ContainerRunState.TERMINATED,
                        exitCode, ExitCodeSource.POD, null, "Error", "Error", null, 0, primary)),
                List.of(new FailureContext.ContainerLog(STEP, false, LogStatus.COLLECTED, false, log)),
                List.of(), List.of());
    }
}
