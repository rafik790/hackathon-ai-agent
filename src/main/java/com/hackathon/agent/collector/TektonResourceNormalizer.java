package com.hackathon.agent.collector;

import com.hackathon.agent.kubernetes.TektonResourceClient;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Converts raw Tekton resources into normalized snapshots. Only the fields needed for monitoring are
 * read; resources without the identifying metadata (uid, name, namespace) are skipped.
 */
@Component
public class TektonResourceNormalizer {

    static final String LABEL_PIPELINE = "tekton.dev/pipeline";
    static final String LABEL_PIPELINE_RUN = "tekton.dev/pipelineRun";
    static final String LABEL_PIPELINE_TASK = "tekton.dev/pipelineTask";
    static final String LABEL_TASK = "tekton.dev/task";

    private static final Logger log = LoggerFactory.getLogger(TektonResourceNormalizer.class);

    public Optional<PipelineRunSnapshot> toPipelineRun(GenericKubernetesResource resource) {
        ObjectMeta metadata = resource.getMetadata();
        if (!hasIdentity(metadata)) {
            log.warn("Skipping PipelineRun without uid/name/namespace");
            return Optional.empty();
        }
        Map<String, String> labels = labels(metadata);
        String pipelineName = firstNonBlank(
                labels.get(LABEL_PIPELINE),
                asString(path(resource, "spec", "pipelineRef", "name")));
        return Optional.of(new PipelineRunSnapshot(
                metadata.getUid(),
                metadata.getName(),
                metadata.getNamespace(),
                pipelineName,
                parseTime(metadata.getCreationTimestamp()),
                executionState(resource)));
    }

    public Optional<TaskRunSnapshot> toTaskRun(GenericKubernetesResource resource) {
        ObjectMeta metadata = resource.getMetadata();
        if (!hasIdentity(metadata)) {
            log.warn("Skipping TaskRun without uid/name/namespace");
            return Optional.empty();
        }
        Map<String, String> labels = labels(metadata);
        Optional<OwnerReference> owner = pipelineRunOwner(metadata);
        String taskName = firstNonBlank(
                labels.get(LABEL_PIPELINE_TASK),
                labels.get(LABEL_TASK),
                asString(path(resource, "spec", "taskRef", "name")),
                metadata.getName());
        return Optional.of(new TaskRunSnapshot(
                metadata.getUid(),
                metadata.getName(),
                metadata.getNamespace(),
                owner.map(OwnerReference::getUid).orElse(null),
                firstNonBlank(owner.map(OwnerReference::getName).orElse(null), labels.get(LABEL_PIPELINE_RUN)),
                labels.get(LABEL_PIPELINE),
                taskName,
                parseTime(metadata.getCreationTimestamp()),
                executionState(resource)));
    }

    private ExecutionState executionState(GenericKubernetesResource resource) {
        Instant startTime = parseTime(asString(path(resource, "status", "startTime")));
        Instant completionTime = parseTime(asString(path(resource, "status", "completionTime")));
        TektonStatusMapper.StatusInfo statusInfo = TektonStatusMapper.map(conditions(resource), startTime != null);
        return new ExecutionState(
                statusInfo.status(),
                startTime,
                completionTime,
                DurationCalculator.durationMs(startTime, completionTime),
                statusInfo.failureReason(),
                statusInfo.failureMessage());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> conditions(GenericKubernetesResource resource) {
        Object conditions = path(resource, "status", "conditions");
        if (conditions instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    private static Optional<OwnerReference> pipelineRunOwner(ObjectMeta metadata) {
        if (metadata.getOwnerReferences() == null) {
            return Optional.empty();
        }
        return metadata.getOwnerReferences().stream()
                .filter(ref -> TektonResourceClient.PIPELINE_RUN_KIND.equals(ref.getKind()))
                .findFirst();
    }

    /** Navigates nested maps of the resource's additional properties (spec/status). */
    private static Object path(GenericKubernetesResource resource, String... keys) {
        Object current = resource.getAdditionalProperties();
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    static Instant parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            log.debug("Ignoring unparseable timestamp '{}'", value);
            return null;
        }
    }

    private static boolean hasIdentity(ObjectMeta metadata) {
        return metadata != null
                && !isBlank(metadata.getUid())
                && !isBlank(metadata.getName())
                && !isBlank(metadata.getNamespace());
    }

    private static Map<String, String> labels(ObjectMeta metadata) {
        return metadata.getLabels() == null ? Map.of() : metadata.getLabels();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
