package com.hackathon.agent.evidence.parser;

import com.hackathon.agent.evidence.model.ContainerRunState;
import com.hackathon.agent.evidence.model.ContainerSnapshot;
import com.hackathon.agent.evidence.model.ContainerType;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import com.hackathon.agent.evidence.model.TaskRunAttempt;
import com.hackathon.agent.evidence.model.TaskRunDetails;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads attempts, steps and sidecars from the status of a Tekton TaskRun.
 * <p>
 * When a TaskRun is retried Tekton archives its status in {@code status.retriesStatus}; the index there is the
 * attempt number and the current status is the last attempt.
 */
@Component
public class TaskRunEvidenceParser {

    /** Message Tekton writes when it marks a step as terminated because it removed the Pod. */
    private static final Pattern POD_TERMINATED_MESSAGE = Pattern.compile("Step .{0,512} terminated as pod .{0,512} is terminated");

    public TaskRunDetails parse(GenericKubernetesResource taskRun) {
        Map<?, ?> status = map(taskRun.getAdditionalProperties().get("status"));
        List<TaskRunAttempt> attempts = new ArrayList<>();
        for (Object retry : list(status.get("retriesStatus"))) {
            attempts.add(attempt(attempts.size(), map(retry)));
        }
        attempts.add(attempt(attempts.size(), status));
        boolean failed = "False".equalsIgnoreCase(text(succeededCondition(status).get("status")));
        return new TaskRunDetails(taskRun.getMetadata().getUid(), failed, List.copyOf(attempts));
    }

    private static TaskRunAttempt attempt(int number, Map<?, ?> status) {
        String reason = text(succeededCondition(status).get("reason"));
        String podName = text(status.get("podName"));
        if (podName == null) {
            // Without a Pod nothing ran; steps may still be left over from the attempt before a retry.
            return new TaskRunAttempt(number, null, reason, List.of());
        }
        List<ContainerSnapshot> containers = new ArrayList<>();
        List<?> steps = list(status.get("steps"));
        for (int i = 0; i < steps.size(); i++) {
            addContainer(containers, map(steps.get(i)), ContainerType.STEP, i, reason);
        }
        List<?> sidecars = list(status.get("sidecars"));
        for (int i = 0; i < sidecars.size(); i++) {
            addContainer(containers, map(sidecars.get(i)), ContainerType.SIDECAR, steps.size() + i, reason);
        }
        return new TaskRunAttempt(number, podName, reason, List.copyOf(containers));
    }

    private static void addContainer(List<ContainerSnapshot> target, Map<?, ?> state, ContainerType type, int ordinal,
                                     String taskRunReason) {
        String name = text(state.get("name"));
        String container = text(state.get("container"));
        if (container == null) {
            return;
        }
        Map<?, ?> terminated = map(state.get("terminated"));
        Map<?, ?> waiting = map(state.get("waiting"));
        Map<?, ?> running = map(state.get("running"));
        ContainerRunState runState = !terminated.isEmpty() ? ContainerRunState.TERMINATED
                : !waiting.isEmpty() ? ContainerRunState.WAITING
                : state.get("running") != null ? ContainerRunState.RUNNING : ContainerRunState.UNKNOWN;
        Integer exitCode = integer(terminated.get("exitCode"));
        target.add(new ContainerSnapshot(
                container,
                type,
                type == ContainerType.STEP ? name : null,
                ordinal,
                runState,
                exitCode,
                exitCode == null ? null : exitCodeSource(terminated, taskRunReason),
                integer(terminated.get("signal")),
                firstText(terminated.get("reason"), waiting.get("reason")),
                text(state.get("terminationReason")),
                firstText(terminated.get("message"), waiting.get("message")),
                null,
                time(firstText(terminated.get("startedAt"), running.get("startedAt"))),
                time(text(terminated.get("finishedAt")))));
    }

    /**
     * Tekton fills in exit code 1 for unfinished steps when it removes their Pod, using the reason of the
     * TaskRun (e.g. TaskRunTimeout) as the termination reason of the step.
     */
    private static ExitCodeSource exitCodeSource(Map<?, ?> terminated, String taskRunReason) {
        String reason = text(terminated.get("reason"));
        String message = text(terminated.get("message"));
        boolean synthetic = (reason != null && reason.equals(taskRunReason))
                || (message != null && POD_TERMINATED_MESSAGE.matcher(message).matches());
        return synthetic ? ExitCodeSource.SYNTHETIC : ExitCodeSource.TASKRUN;
    }

    private static Map<?, ?> succeededCondition(Map<?, ?> status) {
        return list(status.get("conditions")).stream()
                .<Map<?, ?>>map(TaskRunEvidenceParser::map)
                .filter(condition -> "Succeeded".equals(condition.get("type")))
                .findFirst()
                .orElse(Map.of());
    }

    static Instant time(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String firstText(Object first, Object second) {
        return text(first) != null ? text(first) : text(second);
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? Integer.valueOf(number.intValue()) : null;
    }
}
