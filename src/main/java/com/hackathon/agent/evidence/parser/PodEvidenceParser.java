package com.hackathon.agent.evidence.parser;

import com.hackathon.agent.evidence.model.ContainerRunState;
import com.hackathon.agent.evidence.model.ContainerSnapshot;
import com.hackathon.agent.evidence.model.ContainerType;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import com.hackathon.agent.evidence.model.PodSnapshot;
import com.hackathon.agent.evidence.model.TaskRunAttempt;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Reads container states from a Tekton Pod and completes them with what Tekton reported about the same steps.
 */
@Component
public class PodEvidenceParser {

    private static final String STEP_PREFIX = "step-";
    private static final String SIDECAR_PREFIX = "sidecar-";
    /** Tekton termination reason of a failed step with {@code onError: continue}. */
    private static final String CONTINUED = "Continued";

    /**
     * A recreated TaskRun gets a Pod with the same name, so the name alone does not identify the Pod.
     */
    public boolean isOwnedBy(Pod pod, String taskRunUid) {
        return taskRunUid != null && pod.getMetadata() != null && pod.getMetadata().getOwnerReferences() != null
                && pod.getMetadata().getOwnerReferences().stream()
                .anyMatch(owner -> "TaskRun".equals(owner.getKind()) && taskRunUid.equals(owner.getUid()));
    }

    public PodSnapshot parse(Pod pod, TaskRunAttempt attempt) {
        Map<String, ContainerSnapshot> reportedByTekton = attempt.containers().stream()
                .collect(Collectors.toMap(ContainerSnapshot::name, Function.identity(), (first, second) -> first));
        List<ContainerSnapshot> containers = new ArrayList<>();
        String phase = null;
        String reason = null;
        String message = null;
        if (pod.getStatus() != null) {
            phase = pod.getStatus().getPhase();
            reason = pod.getStatus().getReason();
            message = pod.getStatus().getMessage();
            List<Container> initSpecs = pod.getSpec() == null ? List.of() : pod.getSpec().getInitContainers();
            List<Container> specs = pod.getSpec() == null ? List.of() : pod.getSpec().getContainers();
            for (ContainerStatus status : pod.getStatus().getInitContainerStatuses()) {
                containers.add(container(status, initContainerType(status.getName()), initSpecs, reportedByTekton));
            }
            for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
                containers.add(container(status, containerType(status.getName()), specs, reportedByTekton));
            }
        }
        return new PodSnapshot(pod.getMetadata().getName(), pod.getMetadata().getUid(), phase, reason, message,
                List.copyOf(containers));
    }

    /** Steps are regular containers; containers without a Tekton prefix were injected next to them. */
    static ContainerType containerType(String name) {
        return name.startsWith(STEP_PREFIX) ? ContainerType.STEP : ContainerType.SIDECAR;
    }

    /** Native sidecars are init containers that keep the Tekton sidecar prefix. */
    static ContainerType initContainerType(String name) {
        return name.startsWith(SIDECAR_PREFIX) ? ContainerType.SIDECAR : ContainerType.INIT;
    }

    private static ContainerSnapshot container(ContainerStatus status, ContainerType type, List<Container> specs,
                                               Map<String, ContainerSnapshot> reportedByTekton) {
        ContainerSnapshot tekton = reportedByTekton.get(status.getName());
        ContainerState state = status.getState();
        ContainerStateTerminated terminated = terminated(status.getState());
        // A restarted container carries the interesting outcome in its previous state.
        ContainerStateTerminated outcome = terminated != null ? terminated : terminated(status.getLastState());
        boolean waiting = state != null && state.getWaiting() != null;

        Integer exitCode = outcome == null ? null : outcome.getExitCode();
        ExitCodeSource source = exitCode == null ? null : ExitCodeSource.POD;
        if (tekton != null && CONTINUED.equals(tekton.tektonTerminationReason()) && tekton.exitCode() != null
                && !tekton.exitCode().equals(exitCode)) {
            // With onError: continue the container exits with 0 and only Tekton knows the code of the step.
            exitCode = tekton.exitCode();
            source = ExitCodeSource.TASKRUN;
        }
        String reason = waiting ? state.getWaiting().getReason() : outcome == null ? null : outcome.getReason();
        String message = waiting ? state.getWaiting().getMessage() : outcome == null ? null : outcome.getMessage();
        String startedAt = outcome != null ? outcome.getStartedAt()
                : state != null && state.getRunning() != null ? state.getRunning().getStartedAt() : null;

        return new ContainerSnapshot(
                status.getName(),
                type,
                type == ContainerType.STEP ? stepName(status.getName(), tekton) : null,
                ordinal(status.getName(), specs),
                runState(state),
                exitCode,
                source,
                outcome == null ? null : outcome.getSignal(),
                reason,
                tekton == null ? null : tekton.tektonTerminationReason(),
                message,
                status.getRestartCount(),
                TaskRunEvidenceParser.time(startedAt),
                outcome == null ? null : TaskRunEvidenceParser.time(outcome.getFinishedAt()));
    }

    private static ContainerStateTerminated terminated(ContainerState state) {
        return state == null ? null : state.getTerminated();
    }

    private static ContainerRunState runState(ContainerState state) {
        if (state == null) {
            return ContainerRunState.UNKNOWN;
        }
        return state.getTerminated() != null ? ContainerRunState.TERMINATED
                : state.getWaiting() != null ? ContainerRunState.WAITING
                : state.getRunning() != null ? ContainerRunState.RUNNING : ContainerRunState.UNKNOWN;
    }

    private static String stepName(String container, ContainerSnapshot tekton) {
        return tekton != null && tekton.stepName() != null ? tekton.stepName()
                : container.substring(STEP_PREFIX.length());
    }

    /** Position in the Pod spec, which is the execution order; container statuses are not ordered that way. */
    private static Integer ordinal(String name, List<Container> specs) {
        for (int i = 0; i < specs.size(); i++) {
            if (name.equals(specs.get(i).getName())) {
                return i;
            }
        }
        return null;
    }
}
