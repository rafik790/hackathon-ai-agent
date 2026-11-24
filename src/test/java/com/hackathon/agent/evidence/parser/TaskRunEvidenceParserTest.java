package com.hackathon.agent.evidence.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.evidence.model.ContainerRunState;
import com.hackathon.agent.evidence.model.ContainerSnapshot;
import com.hackathon.agent.evidence.model.ContainerType;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import com.hackathon.agent.evidence.model.TaskRunAttempt;
import com.hackathon.agent.evidence.model.TaskRunDetails;
import com.hackathon.agent.support.EvidenceFixtures;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TaskRunEvidenceParserTest {

    private final TaskRunEvidenceParser parser = new TaskRunEvidenceParser();

    @Test
    void taskRunWithoutRetriesHasOneAttempt() {
        TaskRunDetails details = parser.parse(EvidenceFixtures.failedTaskRun("build", "uid-1"));

        assertThat(details.uid()).isEqualTo("uid-1");
        assertThat(details.failed()).isTrue();
        TaskRunAttempt attempt = details.attempts().getFirst();
        assertThat(details.attempts()).hasSize(1);
        assertThat(attempt.attempt()).isZero();
        assertThat(attempt.podName()).isEqualTo("build-pod");
        assertThat(attempt.reason()).isEqualTo("Failed");
        ContainerSnapshot step = attempt.containers().getFirst();
        assertThat(step.name()).isEqualTo("step-test");
        assertThat(step.stepName()).isEqualTo("test");
        assertThat(step.type()).isEqualTo(ContainerType.STEP);
        assertThat(step.state()).isEqualTo(ContainerRunState.TERMINATED);
        assertThat(step.exitCode()).isEqualTo(2);
        assertThat(step.exitCodeSource()).isEqualTo(ExitCodeSource.TASKRUN);
        assertThat(step.startedAt()).isEqualTo(Instant.parse("2026-11-21T10:00:05Z"));
    }

    @Test
    void archivedRetriesComeBeforeTheCurrentAttempt() {
        TaskRunDetails details = parser.parse(EvidenceFixtures.retriedTaskRun("build", "uid-1"));

        assertThat(details.attempts()).extracting(TaskRunAttempt::attempt).containsExactly(0, 1);
        assertThat(details.attempts()).extracting(TaskRunAttempt::podName)
                .containsExactly("build-pod", "build-pod-retry1");
        assertThat(details.attempts()).extracting(TaskRunAttempt::reason).containsExactly("TaskRunTimeout", "Failed");
    }

    @Test
    void exitCodesFilledInByTektonAreSynthetic() {
        TaskRunDetails details = parser.parse(EvidenceFixtures.retriedTaskRun("build", "uid-1"));

        assertThat(details.attempts().get(0).containers().getFirst().exitCodeSource()).isEqualTo(ExitCodeSource.SYNTHETIC);
        assertThat(details.attempts().get(1).containers().getFirst().exitCodeSource()).isEqualTo(ExitCodeSource.TASKRUN);
    }

    @Test
    void attemptWithoutPodIgnoresStepsLeftOverFromAnEarlierAttempt() {
        TaskRunDetails details = parser.parse(EvidenceFixtures.resource("""
                {"metadata": {"name": "a", "namespace": "ci", "uid": "u"}, "status": {"podName": "",
                 "conditions": [{"type": "Succeeded", "status": "False", "reason": "PodCreationFailed"}],
                 "steps": [{"name": "old", "container": "step-old", "terminated": {"exitCode": 1}}]}}"""));

        assertThat(details.attempts().getFirst().podName()).isNull();
        assertThat(details.attempts().getFirst().containers()).isEmpty();
    }

    @Test
    void missingOrRunningStatusIsNotFailed() {
        assertThat(parser.parse(EvidenceFixtures.resource(
                "{\"metadata\": {\"name\": \"a\", \"uid\": \"u\"}}")).failed()).isFalse();
        assertThat(parser.parse(EvidenceFixtures.resource("""
                {"metadata": {"name": "a", "uid": "u"}, "status": {"podName": "a-pod",
                 "conditions": [{"type": "Succeeded", "status": "Unknown", "reason": "ToBeRetried"}]}}""")).failed())
                .isFalse();
    }

    @Test
    void sidecarsAreRecognized() {
        TaskRunDetails details = parser.parse(EvidenceFixtures.resource("""
                {"metadata": {"name": "a", "uid": "u"}, "status": {"podName": "a-pod",
                 "steps": [{"name": "s", "container": "step-s", "waiting": {"reason": "ImagePullBackOff"}}],
                 "sidecars": [{"name": "db", "container": "sidecar-db", "running": {"startedAt": "2026-11-21T10:00:00Z"}}]}}"""));

        assertThat(details.attempts().getFirst().containers())
                .extracting(ContainerSnapshot::type, ContainerSnapshot::state, ContainerSnapshot::ordinal)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ContainerType.STEP, ContainerRunState.WAITING, 0),
                        org.assertj.core.groups.Tuple.tuple(ContainerType.SIDECAR, ContainerRunState.RUNNING, 1));
    }
}
