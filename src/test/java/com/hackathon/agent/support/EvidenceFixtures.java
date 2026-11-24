package com.hackathon.agent.support;

import com.hackathon.agent.config.EvidenceProperties;
import com.hackathon.agent.evidence.model.ContainerRunState;
import com.hackathon.agent.evidence.model.ContainerSnapshot;
import com.hackathon.agent.evidence.model.ContainerType;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.utils.Serialization;

/**
 * Tekton TaskRuns and Pods as returned by the Kubernetes API.
 */
public final class EvidenceFixtures {

    private EvidenceFixtures() {
    }

    public static EvidenceProperties properties() {
        return new EvidenceProperties(true, 30000, 0, 24, 10, 2, 500, 65536, 262144, 14, 5, 60, 1000);
    }

    public static GenericKubernetesResource resource(String json) {
        return Serialization.unmarshal(json, GenericKubernetesResource.class);
    }

    public static Pod pod(String json) {
        return Serialization.unmarshal(json, Pod.class);
    }

    /** A TaskRun that failed on its first attempt: step "test" exited with 2 and "publish" was skipped. */
    public static GenericKubernetesResource failedTaskRun(String name, String uid) {
        return resource(taskRunJson(name, uid, """
                "podName": "%s-pod",
                "conditions": [{"type": "Succeeded", "status": "False", "reason": "Failed", "message": "exit 2"}],
                %s""".formatted(name, STEPS)));
    }

    /** A TaskRun that was retried once and failed both times. */
    public static GenericKubernetesResource retriedTaskRun(String name, String uid) {
        return resource(taskRunJson(name, uid, """
                "podName": "%s-pod-retry1",
                "conditions": [{"type": "Succeeded", "status": "False", "reason": "Failed"}],
                %s,
                "retriesStatus": [{
                  "podName": "%s-pod",
                  "conditions": [{"type": "Succeeded", "status": "False", "reason": "TaskRunTimeout"}],
                  "steps": [{"name": "test", "container": "step-test", "terminationReason": "TaskRunTimeout",
                             "terminated": {"exitCode": 1, "reason": "TaskRunTimeout",
                                            "message": "Step test terminated as pod %s-pod is terminated"}}]
                }]""".formatted(name, STEPS, name, name)));
    }

    public static GenericKubernetesResource taskRunWithoutPod(String name, String uid) {
        return resource(taskRunJson(name, uid, """
                "podName": "",
                "conditions": [{"type": "Succeeded", "status": "False", "reason": "TaskRunValidationFailed"}]"""));
    }

    private static final String STEPS = """
            "steps": [
              {"name": "test", "container": "step-test", "terminationReason": "Error",
               "terminated": {"exitCode": 2, "reason": "Error", "startedAt": "2026-11-21T10:00:05Z",
                              "finishedAt": "2026-11-21T10:00:30Z"}},
              {"name": "publish", "container": "step-publish", "terminationReason": "Skipped",
               "terminated": {"exitCode": 1, "reason": "Error"}}
            ]""";

    private static String taskRunJson(String name, String uid, String status) {
        return """
                {"apiVersion": "tekton.dev/v1", "kind": "TaskRun",
                 "metadata": {"name": "%s", "namespace": "ci", "uid": "%s"},
                 "status": {%s}}""".formatted(name, uid, status);
    }

    /** Pod of {@link #failedTaskRun}. Container statuses are ordered by name, unlike the spec. */
    public static Pod failedPod(String podName, String taskRunUid) {
        return pod("""
                {"apiVersion": "v1", "kind": "Pod",
                 "metadata": {"name": "%s", "namespace": "ci", "uid": "pod-%s",
                   "ownerReferences": [{"apiVersion": "tekton.dev/v1", "kind": "TaskRun", "name": "x", "uid": "%s"}]},
                 "spec": {"initContainers": [{"name": "prepare"}],
                          "containers": [{"name": "step-test"}, {"name": "step-publish"}, {"name": "sidecar-db"}]},
                 "status": {"phase": "Failed", "reason": "ExampleReason", "message": "pod message",
                   "initContainerStatuses": [{"name": "prepare", "restartCount": 0,
                     "state": {"terminated": {"exitCode": 0, "reason": "Completed"}}}],
                   "containerStatuses": [
                     {"name": "sidecar-db", "restartCount": 1,
                      "state": {"terminated": {"exitCode": 128, "reason": "StartError"}},
                      "lastState": {"terminated": {"exitCode": 0, "reason": "Completed"}}},
                     {"name": "step-publish", "restartCount": 0,
                      "state": {"terminated": {"exitCode": 1, "reason": "Error"}}},
                     {"name": "step-test", "restartCount": 0,
                      "state": {"terminated": {"exitCode": 2, "reason": "Error", "signal": 9,
                        "message": "password=hunter2secret", "startedAt": "2026-11-21T10:00:05Z",
                        "finishedAt": "2026-11-21T10:00:30Z"}}}]}}""".formatted(podName, podName, taskRunUid));
    }

    public static ContainerSnapshot container(String name, ContainerType type, int ordinal, ContainerRunState state,
                                              Integer exitCode, String reason, String tektonReason, int restarts) {
        return new ContainerSnapshot(name, type, null, ordinal, state, exitCode,
                exitCode == null ? null : ExitCodeSource.POD, null, reason, tektonReason, null, restarts, null, null);
    }
}
