package com.hackathon.agent.support;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds Tekton v1 resources shaped like the objects returned by the Kubernetes API.
 */
public final class TektonFixtures {

    private TektonFixtures() {
    }

    public static GenericKubernetesResource pipelineRun(String uid, String name, String namespace, String pipeline,
                                                        String conditionStatus, String reason, String message,
                                                        String startTime, String completionTime) {
        GenericKubernetesResource resource = base("PipelineRun", uid, name, namespace);
        resource.getMetadata().setLabels(Map.of("tekton.dev/pipeline", pipeline));
        resource.getAdditionalProperties().put("spec", Map.of("pipelineRef", Map.of("name", pipeline)));
        resource.getAdditionalProperties().put("status", status(conditionStatus, reason, message, startTime, completionTime));
        return resource;
    }

    public static GenericKubernetesResource taskRun(String uid, String name, String namespace, String pipelineRunUid,
                                                    String pipelineRunName, String pipeline, String pipelineTask,
                                                    String conditionStatus, String reason, String message,
                                                    String startTime, String completionTime) {
        GenericKubernetesResource resource = base("TaskRun", uid, name, namespace);
        Map<String, String> labels = new HashMap<>();
        labels.put("tekton.dev/pipeline", pipeline);
        labels.put("tekton.dev/pipelineRun", pipelineRunName);
        labels.put("tekton.dev/pipelineTask", pipelineTask);
        resource.getMetadata().setLabels(labels);
        if (pipelineRunUid != null) {
            resource.getMetadata().setOwnerReferences(List.of(new OwnerReferenceBuilder()
                    .withApiVersion("tekton.dev/v1").withKind("PipelineRun")
                    .withName(pipelineRunName).withUid(pipelineRunUid).withController(true)
                    .build()));
        }
        resource.getAdditionalProperties().put("spec", Map.of("taskRef", Map.of("name", pipelineTask + "-task")));
        resource.getAdditionalProperties().put("status", status(conditionStatus, reason, message, startTime, completionTime));
        return resource;
    }

    private static GenericKubernetesResource base(String kind, String uid, String name, String namespace) {
        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion("tekton.dev/v1");
        resource.setKind(kind);
        resource.setMetadata(new ObjectMetaBuilder()
                .withUid(uid).withName(name).withNamespace(namespace)
                .withCreationTimestamp("2026-11-21T09:58:01Z")
                .build());
        resource.setAdditionalProperties(new HashMap<>());
        return resource;
    }

    private static Map<String, Object> status(String conditionStatus, String reason, String message,
                                              String startTime, String completionTime) {
        Map<String, Object> status = new HashMap<>();
        if (startTime != null) {
            status.put("startTime", startTime);
        }
        if (completionTime != null) {
            status.put("completionTime", completionTime);
        }
        if (conditionStatus != null) {
            Map<String, Object> condition = new HashMap<>();
            condition.put("type", "Succeeded");
            condition.put("status", conditionStatus);
            condition.put("lastTransitionTime", "2026-11-21T10:01:17Z");
            if (reason != null) {
                condition.put("reason", reason);
            }
            if (message != null) {
                condition.put("message", message);
            }
            status.put("conditions", new ArrayList<>(List.of(condition)));
        }
        return status;
    }
}
