package com.hackathon.agent.evidence.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.evidence.model.ContainerRunState;
import com.hackathon.agent.evidence.model.ContainerSnapshot;
import com.hackathon.agent.evidence.model.ContainerType;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import com.hackathon.agent.evidence.model.PodSnapshot;
import com.hackathon.agent.evidence.model.TaskRunAttempt;
import com.hackathon.agent.support.EvidenceFixtures;
import io.fabric8.kubernetes.api.model.Pod;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PodEvidenceParserTest {

    private final PodEvidenceParser parser = new PodEvidenceParser();
    private final TaskRunAttempt attempt = new TaskRunEvidenceParser()
            .parse(EvidenceFixtures.failedTaskRun("build", "uid-1")).attempts().getFirst();

    @Test
    void ownerUidMustMatchTheTaskRun() {
        Pod pod = EvidenceFixtures.failedPod("build-pod", "uid-1");

        assertThat(parser.isOwnedBy(pod, "uid-1")).isTrue();
        assertThat(parser.isOwnedBy(pod, "uid-of-recreated-taskrun")).isFalse();
        assertThat(parser.isOwnedBy(pod, null)).isFalse();

        pod.getMetadata().getOwnerReferences().getFirst().setKind("Job");
        assertThat(parser.isOwnedBy(pod, "uid-1")).isFalse();

        pod.getMetadata().setOwnerReferences(List.of());
        assertThat(parser.isOwnedBy(pod, "uid-1")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"step-test, STEP", "sidecar-db, SIDECAR", "istio-proxy, SIDECAR"})
    void identifiesRegularContainers(String name, ContainerType expected) {
        assertThat(PodEvidenceParser.containerType(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"prepare, INIT", "place-scripts, INIT", "sidecar-db, SIDECAR"})
    void identifiesInitContainers(String name, ContainerType expected) {
        assertThat(PodEvidenceParser.initContainerType(name)).isEqualTo(expected);
    }

    @Test
    void readsPodAndContainerDetails() {
        PodSnapshot pod = parser.parse(EvidenceFixtures.failedPod("build-pod", "uid-1"), attempt);

        assertThat(pod.uid()).isEqualTo("pod-build-pod");
        assertThat(pod.phase()).isEqualTo("Failed");
        assertThat(pod.reason()).isEqualTo("ExampleReason");
        assertThat(pod.message()).isEqualTo("pod message");
        Map<String, ContainerSnapshot> byName = byName(pod);
        assertThat(byName).containsOnlyKeys("prepare", "step-test", "step-publish", "sidecar-db");
        ContainerSnapshot step = byName.get("step-test");
        assertThat(step.ordinal()).isZero();
        assertThat(step.exitCode()).isEqualTo(2);
        assertThat(step.exitCodeSource()).isEqualTo(ExitCodeSource.POD);
        assertThat(step.signal()).isEqualTo(9);
        assertThat(step.tektonTerminationReason()).isEqualTo("Error");
        assertThat(byName.get("step-publish").ordinal()).isEqualTo(1);
        assertThat(byName.get("step-publish").tektonTerminationReason()).isEqualTo("Skipped");
        assertThat(byName.get("sidecar-db").hasRestarted()).isTrue();
    }

    @Test
    void usesTektonExitCodeForContinuedStep() {
        TaskRunAttempt continued = new TaskRunEvidenceParser().parse(EvidenceFixtures.resource("""
                {"metadata": {"name": "a", "uid": "u"}, "status": {"podName": "p", "steps": [
                 {"name": "lint", "container": "step-lint", "terminationReason": "Continued",
                  "terminated": {"exitCode": 7, "reason": "Error"}}]}}""")).attempts().getFirst();
        Pod pod = EvidenceFixtures.pod("""
                {"metadata": {"name": "p", "uid": "x"}, "spec": {"containers": [{"name": "step-lint"}]},
                 "status": {"containerStatuses": [{"name": "step-lint", "restartCount": 0,
                  "state": {"terminated": {"exitCode": 0, "reason": "Completed"}}}]}}""");

        ContainerSnapshot step = parser.parse(pod, continued).containers().getFirst();

        assertThat(step.exitCode()).isEqualTo(7);
        assertThat(step.exitCodeSource()).isEqualTo(ExitCodeSource.TASKRUN);
    }

    @Test
    void restartedContainerKeepsTheOutcomeOfItsPreviousInstance() {
        Pod pod = EvidenceFixtures.pod("""
                {"metadata": {"name": "p", "uid": "x"}, "spec": {},
                 "status": {"initContainerStatuses": [{"name": "sidecar-db", "restartCount": 3,
                  "state": {"waiting": {"reason": "CrashLoopBackOff"}},
                  "lastState": {"terminated": {"exitCode": 139, "reason": "Error"}}}]}}""");

        ContainerSnapshot sidecar = parser.parse(pod, attempt).containers().getFirst();

        assertThat(sidecar.state()).isEqualTo(ContainerRunState.WAITING);
        assertThat(sidecar.reason()).isEqualTo("CrashLoopBackOff");
        assertThat(sidecar.exitCode()).isEqualTo(139);
        assertThat(sidecar.type()).isEqualTo(ContainerType.SIDECAR);
    }

    @Test
    void podWithoutStatusHasNoContainers() {
        Pod pod = EvidenceFixtures.pod("{\"metadata\": {\"name\": \"p\", \"uid\": \"x\"}}");

        assertThat(parser.parse(pod, attempt).containers()).isEmpty();
    }

    private static Map<String, ContainerSnapshot> byName(PodSnapshot pod) {
        return pod.containers().stream().collect(Collectors.toMap(ContainerSnapshot::name, Function.identity()));
    }
}
