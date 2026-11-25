package com.hackathon.agent.ai.parser;

import static com.hackathon.agent.ai.model.EvidenceSource.CONTAINER_LOG;
import static com.hackathon.agent.ai.model.EvidenceSource.CONTAINER_STATUS;
import static com.hackathon.agent.ai.model.EvidenceSource.PIPELINE_RUN;
import static com.hackathon.agent.ai.model.EvidenceSource.POD_STATUS;
import static com.hackathon.agent.ai.model.EvidenceSource.TASK_RUN;
import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.ai.llm.LlmRequest;
import com.hackathon.agent.ai.llm.mock.MockLlmProvider;
import com.hackathon.agent.ai.model.EvidenceSource;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

class AnalysisEvidenceValidatorTest {

    private static final String LOG = """
            2026-11-25T10:00:07Z pushing image ghcr.io/acme/demo:1.4
            \u001B[31merror:\u001B[0m denied: requested access to the resource is denied
            HTTP 403 from https://ghcr.io/v2/
            java.lang.IllegalStateException: push aborted
            """;
    private static final SupportingEvidence LOG_LINE =
            new SupportingEvidence(CONTAINER_LOG, "step-push", "denied: requested access to the resource is denied");

    private final AnalysisEvidenceValidator validator = new AnalysisEvidenceValidator();
    private final FailureContext context = context(LOG, null);

    // ------------------------------------------------------------ supporting evidence

    @Test
    void supportedAnalysisIsNotChanged() {
        FailureAnalysisResult result = result(Severity.HIGH, 0.92, List.of(LOG_LINE),
                "step-push exited with code 1", "The log contains \"denied: requested access\"");

        assertThat(validator.validate(result, context)).isEqualTo(result);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "CONTAINER_LOG    | step-push | denied: requested access to the resource is denied",
            "CONTAINER_LOG    | step-push | error: denied: requested ACCESS   to the resource is denied.",
            "CONTAINER_LOG    | step-push | The registry answered \"denied: requested access to the resource is denied\"",
            "CONTAINER_LOG    | step-push | `denied: requested ... resource is denied`",
            "CONTAINER_LOG    |           | HTTP 403 from https://ghcr.io/v2/",
            "CONTAINER_STATUS | step-oom  | reason OOMKilled",
            "CONTAINER_STATUS | step-oom  | exitCode 137, reason OOMKilled",
            "POD_STATUS       |           | reason Evicted",
            "POD_STATUS       |           | The node was low on resource: memory",
            "TASK_RUN         |           | pod creation quota exceeded",
            "PIPELINE_RUN     |           | Pipeline stopped because of quota limits"})
    void specificEvidenceThatExistsIsKept(EvidenceSource source, String container, String observation) {
        FailureAnalysisResult validated = validate(new SupportingEvidence(source, container, observation));

        assertThat(validated.supportingEvidence()).hasSize(1);
        assertThat(validated.failureCategory()).isEqualTo(FailureCategory.REGISTRY_AUTHENTICATION);
        assertThat(validated.confidence()).isEqualByComparingTo("0.9");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // invented log messages, or messages of another container
            "CONTAINER_LOG    | step-push | connection refused by host",
            "CONTAINER_LOG    | step-push | The log says \"unauthorized: token expired\"",
            "CONTAINER_LOG    | step-oom  | denied: requested access to the resource is denied",
            "CONTAINER_LOG    | step-push | The registry rejected the credentials",
            // invented containers
            "CONTAINER_LOG    | step-deploy | denied: requested access to the resource is denied",
            "CONTAINER_STATUS | step-deploy | exitCode 1",
            // invented or mixed up exit codes
            "CONTAINER_STATUS | step-push | exitCode 127",
            "CONTAINER_STATUS | step-push | exitCode 137",
            "CONTAINER_STATUS | step-push | exited with code 2",
            // invented Kubernetes details
            "CONTAINER_STATUS | step-push | reason ImagePullBackOff",
            "POD_STATUS       |           | reason NodeLost",
            "POD_STATUS       |           | Pod other-run-pod failed",
            "TASK_RUN         |           | TaskRunTimeout after one hour",
            // invented timestamps, hosts and statuses
            "CONTAINER_LOG    | step-push | 2026-11-25T10:05:00Z denied",
            "TASK_RUN         |           | pod creation quota exceeded at 11:22:33",
            "CONTAINER_LOG    | step-push | HTTP 401 from https://ghcr.io/v2/",
            "CONTAINER_LOG    | step-push | https://registry.example/v2/ denied",
            "CONTAINER_STATUS | step-push | restartCount 4",
            "CONTAINER_STATUS | step-push | signal 9"})
    void evidenceThatCannotBeMatchedIsRejected(EvidenceSource source, String container, String observation) {
        FailureAnalysisResult validated = validate(new SupportingEvidence(source, container, observation));

        assertThat(validated.supportingEvidence()).isEmpty();
        assertThat(validated.rootCause()).isEqualTo(AnalysisEvidenceValidator.INSUFFICIENT);
        assertThat(validated.confidence()).isLessThanOrEqualTo(new BigDecimal("0.3"));
    }

    @ParameterizedTest
    @EnumSource(value = EvidenceSource.class, names = {"POD_STATUS", "PIPELINE_RUN", "CONTAINER_LOG", "CONTAINER_STATUS"})
    void evidenceFromSourcesThatAreMissingIsRejected(EvidenceSource source) {
        FailureContext empty = emptyContext("admission webhook denied the request");

        FailureAnalysisResult validated = validator.validate(
                result(Severity.HIGH, 0.9, List.of(new SupportingEvidence(source, null, "admission webhook denied the request"))), empty);

        assertThat(validated.supportingEvidence()).isEmpty();
    }

    @Test
    void rejectedEvidenceLowersConfidenceButKeepsWhatIsSupported() {
        FailureAnalysisResult validated = validator.validate(result(Severity.HIGH, 0.95, List.of(LOG_LINE,
                new SupportingEvidence(CONTAINER_LOG, "step-push", "totally invented line"))), context);

        assertThat(validated.supportingEvidence()).containsExactly(LOG_LINE);
        assertThat(validated.rootCause()).isEqualTo("The registry denied the push.");
        assertThat(validated.confidence()).isEqualByComparingTo("0.6");
    }

    // ------------------------------------------------------------ facts and hypotheses

    @ParameterizedTest
    @ValueSource(strings = {"step-push exited with code 1", "Container step-oom has exit code 137 and reason OOMKilled",
            "The log contains 'push aborted'", "IllegalStateException was thrown while pushing", "The Pod was Evicted",
            "The image ghcr.io/acme/demo:1.4 was pushed at 2026-11-25T10:00:07Z", "requested access to the resource is denied"})
    void factsFromTheContextStayFacts(String fact) {
        FailureAnalysisResult validated = validator.validate(result(Severity.HIGH, 0.9, List.of(LOG_LINE), fact), context);

        assertThat(validated.observedFacts()).containsExactly(fact);
        assertThat(validated.confidence()).isEqualByComparingTo("0.9");
    }

    @ParameterizedTest
    @ValueSource(strings = {"step-push exited with code 137", "step-deploy failed", "step-push exited with code 42",
            "The log contains \"disk quota exceeded\"", "NullPointerException was thrown", "The container was in CrashLoopBackOff",
            "The failure occurred at 2026-11-25T23:59:59Z", "The host 10.1.2.3 was unreachable", "The image ghcr.io/acme/other:2.0 was used"})
    void inventedFactsAreRemoved(String fact) {
        FailureAnalysisResult validated = validator.validate(
                result(Severity.HIGH, 0.9, List.of(LOG_LINE), "step-push exited with code 1", fact), context);

        assertThat(validated.observedFacts()).containsExactly("step-push exited with code 1");
        assertThat(validated.hypotheses()).noneMatch(h -> h.contains(fact));
        assertThat(validated.confidence()).isEqualByComparingTo("0.6");
    }

    @Test
    void interpretationsAmongTheFactsBecomeHypotheses() {
        FailureAnalysisResult validated = validator.validate(result(Severity.HIGH, 0.9, List.of(LOG_LINE),
                "step-push exited with code 1", "Somebody probably rotated credentials yesterday evening"), context);

        assertThat(validated.observedFacts()).containsExactly("step-push exited with code 1");
        assertThat(validated.hypotheses()).containsExactly("The token may have expired",
                "Unverified: Somebody probably rotated credentials yesterday evening");
    }

    @Test
    void hypothesesMayAssumeButNotInventDetails() {
        FailureAnalysisResult result = new FailureAnalysisResult("s", FailureCategory.NETWORK, "The registry denied the push.",
                Severity.HIGH, new BigDecimal("0.900"), List.of("step-push exited with code 1"),
                List.of("A 'firewall rule' may have changed", "step-invented may have failed"), List.of(LOG_LINE), actions(), List.of());

        assertThat(validator.validate(result, context).hypotheses()).containsExactly("A 'firewall rule' may have changed");
    }

    @Test
    void missingFactsAreReplacedByKnownOnes() {
        assertThat(validator.validate(result(Severity.HIGH, 0.9, List.of(LOG_LINE), "step-x exited with code 99"), context).observedFacts())
                .containsExactly("TaskRun run-1 has status FAILED and reason Failed", "Container step-push has exit code 1",
                        "Container step-oom has exit code 137");
    }

    @Test
    void syntheticExitCodesAreNotFacts() {
        FailureContext timeout = context("x", new FailureContext.Container("step-slow", ContainerType.STEP, "slow", 2,
                ContainerRunState.TERMINATED, 99, ExitCodeSource.SYNTHETIC, null, "Error", null, null, 0, true));

        FailureAnalysisResult validated = validator.validate(result(Severity.LOW, 0.5,
                List.of(new SupportingEvidence(CONTAINER_STATUS, "step-slow", "exitCode 99")), "step-slow exited with code 99"), timeout);

        assertThat(validated.supportingEvidence()).isEmpty();
        assertThat(validated.observedFacts()).noneMatch(f -> f.contains("99"));
    }

    // ------------------------------------------------------------ insufficient evidence

    @Test
    void genericEvidenceDoesNotSupportACause() {
        FailureAnalysisResult validated = validator.validate(result(Severity.CRITICAL, 0.95, List.of(
                new SupportingEvidence(CONTAINER_STATUS, "step-push", "exitCode 1"),
                new SupportingEvidence(TASK_RUN, null, "reason Failed"),
                new SupportingEvidence(TASK_RUN, null, "status FAILED"),
                new SupportingEvidence(CONTAINER_LOG, "step-push", "error"))), context);

        assertThat(validated.supportingEvidence()).hasSize(4);
        assertInsufficient(validated);
    }

    @Test
    void stockTektonMessagesDoNotSupportACause() {
        for (String message : List.of("\"step-push\" exited with code 1", "Tasks Completed: 3 (Failed: 1, Cancelled 0), Skipped: 1")) {
            FailureAnalysisResult validated = validator.validate(result(Severity.HIGH, 0.9,
                    List.of(new SupportingEvidence(TASK_RUN, null, message))), withMessage(message));

            assertThat(validated.supportingEvidence()).as(message).hasSize(1);
            assertInsufficient(validated);
        }
    }

    @Test
    void noEvidenceAtAllIsInsufficient() {
        FailureAnalysisResult validated = validator.validate(result(Severity.CRITICAL, 1.0, List.of()), emptyContext(null));

        assertInsufficient(validated);
        assertThat(validated.hypotheses().getFirst()).isEqualTo("Unconfirmed root cause: The registry denied the push.");
        assertThat(validated.recommendedActions().getFirst().action()).startsWith("Inspect the TaskRun");
        assertThat(validated.recommendedActions()).extracting(RecommendedAction::priority)
                .containsExactly(RecommendationPriority.HIGH, RecommendationPriority.LOW);
    }

    @Test
    void inventedRootCauseIsNotEvenKeptAsHypothesis() {
        FailureAnalysisResult result = new FailureAnalysisResult("The push failed.", FailureCategory.NETWORK,
                "The log shows \"connection reset by peer\" from 10.9.8.7.", Severity.HIGH, new BigDecimal("0.900"),
                List.of("step-push exited with code 1"), List.of(), List.of(LOG_LINE), actions(), List.of());

        FailureAnalysisResult validated = validator.validate(result, context);

        assertThat(validated.failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(validated.rootCause()).isEqualTo(AnalysisEvidenceValidator.INSUFFICIENT);
        assertThat(validated.summary()).isEqualTo("Cause not confirmed. step-push reported: " + LOG_LINE.observation());
        assertThat(validated.confidence()).isEqualByComparingTo("0.3");
        assertThat(validated.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(validated.hypotheses()).noneMatch(h -> h.contains("connection reset"));
    }

    @Test
    void honestInsufficientAnswerKeepsItsExplanation() {
        FailureAnalysisResult result = new FailureAnalysisResult("The cause cannot be determined.", FailureCategory.UNKNOWN,
                "Insufficient evidence: the container logs are missing.", Severity.HIGH, new BigDecimal("0.200"),
                List.of("TaskRun run-1 has status FAILED"), List.of("The step may have run out of memory"), List.of(), actions(), List.of());

        FailureAnalysisResult validated = validator.validate(result, emptyContext(null));

        assertThat(validated.rootCause()).isEqualTo("Insufficient evidence: the container logs are missing.");
        assertThat(validated.hypotheses()).isEqualTo(result.hypotheses());
        assertThat(validated.recommendedActions()).isEqualTo(result.recommendedActions());
        assertThat(validated.severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void confidentUnknownWithoutEvidenceIsStillReduced() {
        FailureAnalysisResult result = new FailureAnalysisResult("s", FailureCategory.UNKNOWN, "A bug in the compiler.", Severity.LOW,
                new BigDecimal("0.900"), List.of("step-push exited with code 1"), List.of(), List.of(), actions(), List.of());

        assertInsufficient(validator.validate(result, context));
    }

    @Test
    void mockAnswerStaysRecognizable() {
        FailureAnalysisResult mock = new FailureAnalysisParser(new LogSanitizer())
                .parse(new MockLlmProvider().generate(new LlmRequest("", "", Map.of())).text());

        assertThat(validator.validate(mock, context).summary()).startsWith("[MOCK]");
    }

    // ------------------------------------------------------------ real build log

    /** Source code in the log contains quotation marks without a counterpart, which are easily paired wrongly. */
    @ParameterizedTest
    @ValueSource(strings = {
            "A missing closing quote in DemoController.java line 16 broke compileJava.",
            "Line 16 of DemoController.java, System.out.println(\"I get fired);, has an unclosed string literal, which also causes \"';' expected\" on line 21.",
            "The compiler reported \"unclosed string literal\" for println(\"I get fired); in DemoController.java:16.",
            "javac reports ';' expected at DemoController.java:21, so the task ':compileJava' failed.",
            "The statement `System.out.println(\"I get fired);` lacks its closing quote.",
            "javac failed with \"DemoController.java:16 error unclosed string literal\".",
            "The step-level command \"RUN chmod +x gradlew && ./gradlew clean bootJar --no-daemon\" stopped with exit status 1."})
    void correctConclusionsFromARealLogAreKept(String conclusion) throws Exception {
        SupportingEvidence line = new SupportingEvidence(CONTAINER_LOG, "step-push",
                "/app/src/main/java/com/hackathon/demo/controller/DemoController.java:16: error: unclosed string literal");
        FailureAnalysisResult result = conclusion(conclusion, line);

        FailureAnalysisResult validated = validator.validate(result, context(realLog(), null));

        assertThat(validated).usingRecursiveComparison().ignoringFields("recommendedActions").isEqualTo(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "The log shows \"connection reset by peer\" while compiling.",
            "println(\"I get fired); caused \"cannot find symbol Foo\".",
            "compileJava failed with a NullPointerException.",
            "step-deploy failed before compileJava."})
    void inventedConclusionsFromARealLogAreStillReplaced(String conclusion) throws Exception {
        SupportingEvidence line = new SupportingEvidence(CONTAINER_LOG, "step-push", "> Task :compileJava FAILED");

        FailureAnalysisResult validated = validator.validate(conclusion(conclusion, line), context(realLog(), null));

        assertThat(validated.toString()).doesNotContain(conclusion);
        assertThat(validated.summary()).isEqualTo(
                "Compilation failed in DemoController.java:16: unclosed string literal (missing closing quote).");
        assertThat(validated.confidence()).isEqualByComparingTo("0.8");
    }

    private static FailureAnalysisResult conclusion(String text, SupportingEvidence evidence) {
        return new FailureAnalysisResult(text, FailureCategory.COMPILATION, text, Severity.HIGH, new BigDecimal("0.950"),
                List.of("step-push exited with code 1"), List.of(), List.of(evidence), actions(), List.of());
    }

    private static String realLog() throws Exception {
        return new ClassPathResource("logs/compilation-failure.txt").getContentAsString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ severity

    @Test
    void singleFailedRunIsNotCritical() {
        assertThat(validator.validate(result(Severity.CRITICAL, 0.95, List.of(LOG_LINE)), context).severity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void criticalNeedsVerifiedEvidenceOfAWiderImpact() {
        String log = "etcd: no space left on device";
        SupportingEvidence verified = new SupportingEvidence(CONTAINER_LOG, "step-push", log);
        SupportingEvidence invented = new SupportingEvidence(CONTAINER_LOG, "step-push", "production cluster outage");

        assertThat(validator.validate(result(Severity.CRITICAL, 0.9, List.of(verified)), context(log, null)).severity())
                .isEqualTo(Severity.CRITICAL);
        assertThat(validator.validate(result(Severity.CRITICAL, 0.7, List.of(verified)), context(log, null)).severity())
                .isEqualTo(Severity.HIGH);
        assertThat(validator.validate(result(Severity.CRITICAL, 0.9, List.of(LOG_LINE, invented)), context).severity())
                .isEqualTo(Severity.HIGH);
    }

    @Test
    void uncertainCauseIsAtMostMedium() {
        assertThat(validator.validate(result(Severity.HIGH, 0.4, List.of(LOG_LINE)), context).severity()).isEqualTo(Severity.MEDIUM);
        assertThat(validator.validate(result(Severity.CRITICAL, 0.4, List.of(LOG_LINE)), context).severity()).isEqualTo(Severity.MEDIUM);
    }

    @ParameterizedTest
    @EnumSource(value = Severity.class, names = {"MEDIUM", "LOW"})
    void lowerSeveritiesAreNeverRaised(Severity severity) {
        assertThat(validator.validate(result(severity, 0.95, List.of(LOG_LINE)), context).severity()).isEqualTo(severity);
    }

    // ------------------------------------------------------------ helpers

    private void assertInsufficient(FailureAnalysisResult result) {
        assertThat(result.failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(result.rootCause()).isEqualTo(AnalysisEvidenceValidator.INSUFFICIENT);
        assertThat(result.summary()).startsWith("Insufficient evidence");
        assertThat(result.confidence()).isLessThanOrEqualTo(new BigDecimal("0.3"));
        assertThat(result.severity()).isIn(Severity.MEDIUM, Severity.LOW);
        assertThat(result.observedFacts()).isNotEmpty();
    }

    private FailureAnalysisResult validate(SupportingEvidence evidence) {
        return validator.validate(result(Severity.HIGH, 0.9, List.of(evidence)), context);
    }

    private static FailureAnalysisResult result(Severity severity, double confidence, List<SupportingEvidence> evidence,
                                                String... facts) {
        return new FailureAnalysisResult("The push failed.", FailureCategory.REGISTRY_AUTHENTICATION, "The registry denied the push.",
                severity, BigDecimal.valueOf(confidence).setScale(3), facts.length == 0 ? List.of("TaskRun run-1 has status FAILED") : List.of(facts),
                List.of("The token may have expired"), evidence, actions(), List.of("Check tokens"));
    }

    private static List<RecommendedAction> actions() {
        return List.of(new RecommendedAction(RecommendationPriority.HIGH, "Renew the token", "Access was denied"));
    }

    private static FailureContext context(String log, FailureContext.Container extra) {
        FailureContext.Container push = new FailureContext.Container("step-push", ContainerType.STEP, "push", 0,
                ContainerRunState.TERMINATED, 1, ExitCodeSource.POD, null, "Error", "Error", null, 0, true);
        FailureContext.Container oom = new FailureContext.Container("step-oom", ContainerType.STEP, "oom", 1,
                ContainerRunState.TERMINATED, 137, ExitCodeSource.POD, null, "OOMKilled", null, null, 0, false);
        Instant start = Instant.parse("2026-11-25T10:00:00Z");
        return new FailureContext(
                new FailureContext.PipelineRun("pipeline-1", "p-uid", "demo-build", "ci", ExecutionStatus.FAILED, start, null,
                        "Failed", "Pipeline stopped because of quota limits"),
                new FailureContext.TaskRun("run-1", "t-uid", "build-and-push", "ci", ExecutionStatus.FAILED, start, null, "Failed",
                        "pod creation quota exceeded", 0, 1),
                EvidenceStatus.COLLECTED,
                new FailureContext.Pod("run-1-pod", "pod-uid", "Failed", "Evicted", "The node was low on resource: memory"),
                extra == null ? List.of(push, oom) : List.of(push, oom, extra),
                List.of(new FailureContext.ContainerLog("step-push", false, LogStatus.COLLECTED, false, log)),
                List.of(), List.of());
    }

    private static FailureContext withMessage(String message) {
        FailureContext c = context("no useful output", null);
        return new FailureContext(c.pipelineRun(), new FailureContext.TaskRun("run-1", "t-uid", "build-and-push", "ci",
                ExecutionStatus.FAILED, null, null, "Failed", message, 0, 1), c.evidenceStatus(), c.pod(), c.containers(), c.logs(),
                List.of(), List.of());
    }

    private static FailureContext emptyContext(String message) {
        return new FailureContext(null, new FailureContext.TaskRun("run-1", "t-uid", "build", "ci", ExecutionStatus.FAILED, null, null,
                "Failed", message, 0, 1), EvidenceStatus.NO_POD, null, List.of(), List.of(), List.of(), List.of());
    }
}
