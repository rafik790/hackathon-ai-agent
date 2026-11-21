package com.hackathon.agent.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.support.TektonFixtures;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TektonResourceNormalizerTest {

    private final TektonResourceNormalizer normalizer = new TektonResourceNormalizer();

    @Test
    void normalizesFailedPipelineRun() {
        GenericKubernetesResource resource = TektonFixtures.pipelineRun("uid-1", "build-abc", "ci", "build",
                "False", "Failed", "Tasks Completed: 2 (Failed: 1)", "2026-11-21T10:00:00Z", "2026-11-21T10:02:30Z");

        PipelineRunSnapshot snapshot = normalizer.toPipelineRun(resource).orElseThrow();

        assertThat(snapshot.uid()).isEqualTo("uid-1");
        assertThat(snapshot.name()).isEqualTo("build-abc");
        assertThat(snapshot.namespace()).isEqualTo("ci");
        assertThat(snapshot.pipelineName()).isEqualTo("build");
        assertThat(snapshot.resourceCreatedAt()).isEqualTo(Instant.parse("2026-11-21T09:58:01Z"));
        assertThat(snapshot.state().status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(snapshot.state().startTime()).isEqualTo(Instant.parse("2026-11-21T10:00:00Z"));
        assertThat(snapshot.state().completionTime()).isEqualTo(Instant.parse("2026-11-21T10:02:30Z"));
        assertThat(snapshot.state().durationMs()).isEqualTo(150_000L);
        assertThat(snapshot.state().failureReason()).isEqualTo("Failed");
        assertThat(snapshot.state().failureMessage()).contains("Failed: 1");
    }

    @Test
    void normalizesRealTektonJsonPayload() {
        String json = """
                {
                  "apiVersion": "tekton.dev/v1",
                  "kind": "PipelineRun",
                  "metadata": {
                    "name": "demo-app-run-7xk2p",
                    "namespace": "ci",
                    "uid": "6f1c2c9e-3f5b-4c1e-9a43-1b1f0f6f7a10",
                    "creationTimestamp": "2026-11-21T09:58:01Z",
                    "labels": {"tekton.dev/pipeline": "demo-app-pipeline"}
                  },
                  "spec": {"pipelineRef": {"name": "demo-app-pipeline"}, "timeouts": {"pipeline": "1h0m0s"}},
                  "status": {
                    "startTime": "2026-11-21T09:58:02Z",
                    "conditions": [{
                      "type": "Succeeded", "status": "Unknown", "reason": "Running",
                      "message": "Tasks Completed: 1 (Failed: 0, Cancelled 0), Incomplete: 2, Skipped: 0",
                      "lastTransitionTime": "2026-11-21T09:59:00Z"
                    }],
                    "childReferences": [{"apiVersion": "tekton.dev/v1", "kind": "TaskRun", "name": "demo-app-run-7xk2p-clone"}]
                  }
                }""";
        GenericKubernetesResource resource = Serialization.unmarshal(json, GenericKubernetesResource.class);

        PipelineRunSnapshot snapshot = normalizer.toPipelineRun(resource).orElseThrow();

        assertThat(snapshot.pipelineName()).isEqualTo("demo-app-pipeline");
        assertThat(snapshot.state().status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(snapshot.state().completionTime()).isNull();
        assertThat(snapshot.state().durationMs()).isNull();
        assertThat(snapshot.state().failureReason()).isNull();
    }

    @Test
    void pipelineNameFallsBackToPipelineRef() {
        GenericKubernetesResource resource = TektonFixtures.pipelineRun("uid-1", "build-abc", "ci", "build",
                "True", "Succeeded", null, "2026-11-21T10:00:00Z", "2026-11-21T10:01:00Z");
        resource.getMetadata().setLabels(null);

        assertThat(normalizer.toPipelineRun(resource).orElseThrow().pipelineName()).isEqualTo("build");
    }

    @Test
    void newlyCreatedPipelineRunWithoutStatusIsUnknown() {
        GenericKubernetesResource resource = TektonFixtures.pipelineRun("uid-2", "build-new", "ci", "build",
                null, null, null, null, null);
        resource.getAdditionalProperties().remove("status");

        PipelineRunSnapshot snapshot = normalizer.toPipelineRun(resource).orElseThrow();

        assertThat(snapshot.state().status()).isEqualTo(ExecutionStatus.UNKNOWN);
        assertThat(snapshot.state().startTime()).isNull();
    }

    @Test
    void skipsResourcesWithoutIdentity() {
        GenericKubernetesResource resource = TektonFixtures.pipelineRun("uid-3", "x", "ci", "build",
                "True", null, null, null, null);
        resource.getMetadata().setUid(null);

        assertThat(normalizer.toPipelineRun(resource)).isEmpty();
    }

    @Test
    void normalizesTaskRunWithOwnerReference() {
        GenericKubernetesResource resource = TektonFixtures.taskRun("t-1", "build-abc-test", "ci", "uid-1",
                "build-abc", "build", "unit-test", "False", "Failed", "\"step-test\" exited with code 1",
                "2026-11-21T10:00:10Z", "2026-11-21T10:01:00Z");

        TaskRunSnapshot snapshot = normalizer.toTaskRun(resource).orElseThrow();

        assertThat(snapshot.pipelineRunUid()).isEqualTo("uid-1");
        assertThat(snapshot.pipelineRunName()).isEqualTo("build-abc");
        assertThat(snapshot.pipelineName()).isEqualTo("build");
        assertThat(snapshot.taskName()).isEqualTo("unit-test");
        assertThat(snapshot.state().status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(snapshot.state().durationMs()).isEqualTo(50_000L);
    }

    @Test
    void standaloneTaskRunFallsBackToTaskRef() {
        GenericKubernetesResource resource = TektonFixtures.taskRun("t-2", "lint-run", "ci", null,
                null, null, "lint", "True", "Succeeded", null, "2026-11-21T10:00:00Z", "2026-11-21T10:00:05Z");
        resource.getMetadata().setLabels(null);

        TaskRunSnapshot snapshot = normalizer.toTaskRun(resource).orElseThrow();

        assertThat(snapshot.pipelineRunUid()).isNull();
        assertThat(snapshot.pipelineRunName()).isNull();
        assertThat(snapshot.taskName()).isEqualTo("lint-task");
        assertThat(snapshot.state().status()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    void ignoresUnparseableTimestamps() {
        assertThat(TektonResourceNormalizer.parseTime("not-a-date")).isNull();
        assertThat(TektonResourceNormalizer.parseTime("2026-11-21T10:00:00+02:00"))
                .isEqualTo(Instant.parse("2026-11-21T08:00:00Z"));
    }
}
