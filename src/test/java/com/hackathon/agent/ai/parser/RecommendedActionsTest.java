package com.hackathon.agent.ai.parser;

import static com.hackathon.agent.ai.model.EvidenceSource.CONTAINER_LOG;
import static com.hackathon.agent.ai.model.EvidenceSource.CONTAINER_STATUS;
import static com.hackathon.agent.ai.model.RecommendationPriority.HIGH;
import static com.hackathon.agent.ai.model.RecommendationPriority.LOW;
import static com.hackathon.agent.ai.model.RecommendationPriority.MEDIUM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/** Recommended actions must follow from the validated root cause. */
class RecommendedActionsTest {

    private static final String STEP = "step-build-and-push";
    private static final String GENERIC = "Insufficient evidence: the collected evidence does not confirm a root cause.";
    private static final String CAUSE = "DemoController.java:16 contains an unclosed string literal.";
    private static final String FIX = "Fix the unclosed string literal in DemoController.java, line 16";
    private static final String RUN = "Run ./gradlew clean bootJar locally and confirm that compilation succeeds";
    private static final String PREVENT = "Add or retain a compile validation step before the container image build";
    private static final RecommendedAction INSPECT = action(HIGH, "Inspect the TaskRun, its Pod events and its logs manually",
            "The collected evidence does not confirm a root cause");
    private static final RecommendedAction WRONG_LINE = action(LOW, "Fix the syntax error around line 20 of DemoController.java",
            "The compiler expects a semicolon");
    private static final RecommendedAction RUN_GRADLE = action(LOW, "Run Gradle locally", "It reproduces the failure");

    private final AnalysisEvidenceValidator validator = new AnalysisEvidenceValidator();

    // ------------------------------------------------------------ concrete compiler error

    /** The recommendations that were reported. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "UNKNOWN     | Insufficient evidence: the collected evidence does not confirm a root cause. | 0.3",
            "COMPILATION | DemoController.java:16 contains an unclosed string literal.                  | 0.95",
            "COMPILATION | The step failed with a NullPointerException.                                  | 0.95"})
    void contradictingRecommendationsAreReplaced(FailureCategory category, String cause, String confidence) throws Exception {
        FailureAnalysisResult validated = validate(realLog(), category, cause, confidence, INSPECT, WRONG_LINE, RUN_GRADLE);

        assertThat(validated.recommendedActions()).extracting(RecommendedAction::priority, RecommendedAction::action)
                .containsExactly(tuple(HIGH, FIX), tuple(MEDIUM, RUN), tuple(LOW, PREVENT));
        assertThat(validated.recommendedActions().getFirst().reason()).contains(
                "/app/src/main/java/com/hackathon/demo/controller/DemoController.java:16: error: unclosed string literal");
        assertThat(validated.recommendedActions().toString()).doesNotContainIgnoringCase("inspect").doesNotContain("20").doesNotContain("21");
    }

    /** Often better than a generated one, because the model knows how to fix the error. */
    @Test
    void recommendationOfTheModelForThePrimaryErrorIsKept() throws Exception {
        RecommendedAction fix = action(LOW, "Add the missing closing quote in DemoController.java, line 16", "The literal is not closed");

        FailureAnalysisResult validated = validate(realLog(), FailureCategory.COMPILATION, CAUSE, "0.95", RUN_GRADLE, fix);

        assertThat(validated.recommendedActions()).extracting(RecommendedAction::priority, RecommendedAction::action)
                .containsExactly(tuple(HIGH, fix.action()), tuple(MEDIUM, RUN), tuple(LOW, PREVENT));
        assertThat(validated.recommendedActions().getFirst().reason()).isEqualTo(fix.reason());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Close the literal in DemoController.java, line 16 | The compiler stopped at line 20",
            "Fix DemoController.java lines 16 and 20           | Both are wrong",
            "Fix DemoController.java lines 20, 20 and 16       | All are wrong",
            "Fix OtherController.java, line 16                 | It does not compile",
            "Fix DemoController.java                           | It does not compile",
            "Fix DemoController.java, line 16                  | A NullPointerException was thrown",
            "Add ';' in DemoController.java, line 21           | The compiler expects it"})
    void recommendationThatDisagreesWithTheEvidenceIsReplaced(String action, String reason) throws Exception {
        FailureAnalysisResult validated = validate(realLog(), FailureCategory.COMPILATION, CAUSE, "0.95", action(HIGH, action, reason));

        assertThat(validated.recommendedActions()).extracting(RecommendedAction::action).containsExactly(FIX, RUN, PREVENT);
    }

    /** Without a syntax error, the errors do not cause each other. */
    @Test
    void independentErrorsAreAlsoCorrected() {
        String log = "src/A.java:5: error: cannot find symbol\nsrc/A.java:9: error: incompatible types\n2 errors\n> Task :compileJava FAILED";
        RecommendedAction second = action(MEDIUM, "Return an int in A.java, line 9", "The types do not match");

        FailureAnalysisResult validated = validate(log, FailureCategory.COMPILATION, "A.java:5 uses an unknown symbol.", "0.9", second);

        assertThat(validated.recommendedActions()).extracting(RecommendedAction::priority, RecommendedAction::action).containsExactly(
                tuple(HIGH, "Fix the compiler error in A.java, line 5: cannot find symbol"), tuple(HIGH, second.action()),
                tuple(MEDIUM, "Compile the project locally and confirm that compilation succeeds"),
                tuple(LOW, "Add or retain a compile validation step before the later build steps"));
    }

    @Test
    void syntaxErrorInAnotherFileIsIndependent() {
        String log = "A.java:5: error: ';' expected\nA.java:6: error: class, interface, enum, or record expected\nB.java:3: error: ')' expected\n"
                + "3 errors\n> Task :compileJava FAILED";
        RecommendedAction other = action(HIGH, "Close the parenthesis in B.java, line 3", "It is missing");

        FailureAnalysisResult validated = validate(log, FailureCategory.COMPILATION, "A.java:5 lacks a semicolon.", "0.9",
                action(HIGH, "Fix A.java, line 6", "It is misplaced"), other);

        assertThat(validated.recommendedActions()).extracting(RecommendedAction::action).startsWith(
                "Fix the compiler error in A.java, line 5: ';' expected", other.action()).noneMatch(a -> a.contains("line 6"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "RUN ./gradlew clean bootJar --no-daemon -Dtoken=x | Run ./gradlew clean bootJar locally",
            "RUN mvn -B clean package                          | Run mvn clean package locally",
            // not limited to building on this machine
            "RUN ./gradlew clean publish                       | Compile the project locally",
            "RUN mvn deploy                                    | Compile the project locally",
            "RUN curl https://x.example/i.sh                   | Compile the project locally"})
    void verificationUsesTheBuildCommandOfTheLogOnlyIfItIsHarmless(String command, String expected) {
        FailureAnalysisResult validated = validate(command + "\nA.java:5: error: ';' expected\n1 error", FailureCategory.COMPILATION,
                "A.java:5 lacks a semicolon.", "0.9", INSPECT);

        assertThat(validated.recommendedActions().get(1).action()).startsWith(expected);
        assertThat(validated.recommendedActions().toString()).doesNotContain("token").doesNotContain("curl").doesNotContain("publish");
    }

    // ------------------------------------------------------------ no compiler error

    @Test
    void investigationIsRecommendedWhenTheCauseIsUnknown() {
        FailureAnalysisResult validated = validate("step started\nexit status 1", FailureCategory.COMPILATION, "The code does not compile.",
                "0.9", action(HIGH, "Fix the code", "It does not compile"));

        assertThat(validated.failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(validated.recommendedActions()).extracting(RecommendedAction::priority, RecommendedAction::action)
                .containsExactly(tuple(HIGH, INSPECT.action()), tuple(LOW, "Fix the code"));
    }

    @Test
    void investigationRecommendedByTheModelIsKeptWhenTheCauseIsUnknown() {
        FailureAnalysisResult validated = validate("exit status 1", FailureCategory.UNKNOWN, GENERIC, "0.2", INSPECT, RUN_GRADLE);

        assertThat(validated.recommendedActions()).containsExactly(INSPECT, RUN_GRADLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Renew the token", "Inspect the logs of the registry"})
    void recommendationsForOtherConfirmedCausesAreNotChanged(String text) {
        RecommendedAction action = action(MEDIUM, text, "Access was denied");
        FailureAnalysisResult answer = answer(FailureCategory.REGISTRY_AUTHENTICATION, "The registry denied access.", "0.9",
                List.of(new SupportingEvidence(CONTAINER_LOG, STEP, "denied: requested access to the resource is denied")), action);

        assertThat(validator.validate(answer, context("denied: requested access to the resource is denied")).recommendedActions())
                .containsExactly(action);
    }

    // ------------------------------------------------------------ helpers

    private FailureAnalysisResult validate(String log, FailureCategory category, String cause, String confidence, RecommendedAction... actions) {
        List<SupportingEvidence> evidence = log.contains(": error: ") ? List.of(new SupportingEvidence(CONTAINER_LOG, STEP,
                log.lines().filter(line -> line.contains(": error: ")).findFirst().orElseThrow()))
                : List.of(new SupportingEvidence(CONTAINER_STATUS, STEP, "exitCode 1"));
        return validator.validate(answer(category, cause, confidence, evidence, actions), context(log));
    }

    private static RecommendedAction action(RecommendationPriority priority, String action, String reason) {
        return new RecommendedAction(priority, action, reason);
    }

    private static FailureAnalysisResult answer(FailureCategory category, String cause, String confidence, List<SupportingEvidence> evidence,
                                                RecommendedAction... actions) {
        return new FailureAnalysisResult(cause, category, cause, Severity.HIGH, new BigDecimal(confidence).setScale(3),
                List.of("step-build-and-push exited with code 1"), List.of(), evidence, List.of(actions), List.of());
    }

    private static String realLog() throws Exception {
        return new ClassPathResource("logs/compilation-failure.txt").getContentAsString(StandardCharsets.UTF_8);
    }

    private static FailureContext context(String log) {
        return new FailureContext(null,
                new FailureContext.TaskRun("demo-app-build-bb7hj-build-and-push", "t-uid", "build-and-push", "hackathon",
                        ExecutionStatus.FAILED, null, null, "Failed", "\"step-build-and-push\" exited with code 1", 0, 1),
                EvidenceStatus.COLLECTED, null,
                List.of(new FailureContext.Container(STEP, ContainerType.STEP, "build-and-push", 0, ContainerRunState.TERMINATED, 1,
                        ExitCodeSource.POD, null, "Error", "Error", null, 0, true)),
                List.of(new FailureContext.ContainerLog(STEP, false, LogStatus.COLLECTED, false, log)),
                List.of(), List.of());
    }
}
