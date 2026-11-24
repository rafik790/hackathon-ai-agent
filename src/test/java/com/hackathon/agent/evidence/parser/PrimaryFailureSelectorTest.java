package com.hackathon.agent.evidence.parser;

import static com.hackathon.agent.evidence.model.ContainerRunState.RUNNING;
import static com.hackathon.agent.evidence.model.ContainerRunState.TERMINATED;
import static com.hackathon.agent.evidence.model.ContainerRunState.WAITING;
import static com.hackathon.agent.evidence.model.ContainerType.INIT;
import static com.hackathon.agent.evidence.model.ContainerType.SIDECAR;
import static com.hackathon.agent.evidence.model.ContainerType.STEP;
import static com.hackathon.agent.support.EvidenceFixtures.container;
import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.evidence.model.ContainerSnapshot;
import com.hackathon.agent.evidence.parser.PrimaryFailureSelector.Selection;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrimaryFailureSelectorTest {

    private final PrimaryFailureSelector selector = new PrimaryFailureSelector();

    private static final ContainerSnapshot STOPPED_SIDECAR =
            container("sidecar-db", SIDECAR, 9, TERMINATED, 128, "StartError", null, 1);

    @Test
    void firstFailingStepIsPrimaryAndSkippedStepsAreIgnored() {
        Selection selection = selector.select(List.of(
                container("step-c", STEP, 2, TERMINATED, 1, "Error", "Skipped", 0),
                container("step-b", STEP, 1, TERMINATED, 2, "Error", "Error", 0),
                container("step-a", STEP, 0, TERMINATED, 0, "Completed", "Completed", 0)), "Failed");

        assertThat(selection.primary()).isEqualTo("step-b");
        assertThat(names(selection)).containsExactly("step-b");
    }

    @Test
    void withoutTerminationReasonsTheFirstFailingStepInExecutionOrderWins() {
        Selection selection = selector.select(List.of(
                container("step-a", STEP, 2, TERMINATED, 1, "Error", null, 0),
                container("step-z", STEP, 1, TERMINATED, 1, "Error", null, 0)), "Failed");

        assertThat(names(selection)).containsExactly("step-z", "step-a");
    }

    @Test
    void continuedStepIsNotTheCause() {
        assertThat(selector.select(List.of(
                container("step-lint", STEP, 0, TERMINATED, 1, "Error", "Continued", 0),
                container("step-test", STEP, 1, TERMINATED, 3, "Error", "Error", 0)), "Failed").primary())
                .isEqualTo("step-test");
    }

    @Test
    void failedInitContainerComesFirst() {
        assertThat(selector.select(List.of(
                container("step-test", STEP, 0, TERMINATED, 1, "Error", null, 0),
                container("prepare", INIT, 0, TERMINATED, 1, "Error", null, 0)), "Failed").primary())
                .isEqualTo("prepare");
    }

    @Test
    void sidecarsStoppedByTektonAreIgnoredWhenAStepFailed() {
        assertThat(names(selector.select(List.of(STOPPED_SIDECAR,
                container("step-test", STEP, 0, TERMINATED, 1, "Error", "Error", 0)), "Failed")))
                .containsExactly("step-test");
    }

    @Test
    void sidecarIsPrimaryWhenTektonSaysSoOrNothingElseFailed() {
        ContainerSnapshot step = container("step-test", STEP, 0, TERMINATED, 1, "Error", "Error", 0);

        assertThat(selector.select(List.of(step, STOPPED_SIDECAR), "SidecarFailed").primary()).isEqualTo("sidecar-db");
        assertThat(selector.select(List.of(STOPPED_SIDECAR), "Failed").primary()).isEqualTo("sidecar-db");
    }

    @Test
    void outOfMemoryComesBeforeOtherStepFailures() {
        assertThat(selector.select(List.of(
                container("step-a", STEP, 0, TERMINATED, 1, "Error", null, 0),
                container("step-b", STEP, 1, TERMINATED, 137, "OOMKilled", null, 0)), "Failed").primary())
                .isEqualTo("step-b");
    }

    @Test
    void containerThatCannotStartIsPrimary() {
        assertThat(selector.select(List.of(
                container("step-a", STEP, 0, WAITING, null, "ImagePullBackOff", null, 0),
                container("step-b", STEP, 1, WAITING, null, "PodInitializing", null, 0)),
                "TaskRunImagePullFailed").primary()).isEqualTo("step-a");
    }

    @Test
    void runningStepIsRelevantWhenNothingFailed() {
        assertThat(selector.select(List.of(
                container("step-a", STEP, 0, TERMINATED, 0, "Completed", null, 0),
                container("step-b", STEP, 1, RUNNING, null, null, null, 0),
                container("step-c", STEP, 2, RUNNING, null, null, null, 0)), "TaskRunTimeout").primary())
                .isEqualTo("step-b");
    }

    @Test
    void noPrimaryWhenEverythingSucceeded() {
        Selection selection = selector.select(List.of(
                container("step-a", STEP, 0, TERMINATED, 0, "Completed", null, 0)), null);

        assertThat(selection.primary()).isNull();
        assertThat(selection.relevant()).isEmpty();
    }

    private static List<String> names(Selection selection) {
        return selection.relevant().stream().map(ContainerSnapshot::name).toList();
    }
}
