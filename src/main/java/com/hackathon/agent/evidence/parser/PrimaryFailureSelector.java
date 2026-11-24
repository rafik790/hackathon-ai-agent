package com.hackathon.agent.evidence.parser;

import com.hackathon.agent.evidence.model.ContainerRunState;
import com.hackathon.agent.evidence.model.ContainerSnapshot;
import com.hackathon.agent.evidence.model.ContainerType;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides which container caused a failure and which containers are worth collecting logs from.
 * <p>
 * Steps run one after the other, so the first failing step is the cause; Tekton marks the steps after it as
 * skipped. A verdict given by Tekton itself (e.g. SidecarOOM) takes precedence.
 */
@Component
public class PrimaryFailureSelector {

    private static final String OOM_KILLED = "OOMKilled";
    /** Tekton termination reasons of steps that did not cause the failure. */
    private static final Set<String> NOT_A_CAUSE = Set.of("Skipped", "Continued");
    private static final Set<String> START_FAILURES = Set.of("ImagePullBackOff", "ErrImagePull", "InvalidImageName",
            "CreateContainerConfigError", "CreateContainerError", "CrashLoopBackOff", "RunContainerError");

    /**
     * @param primary  the container that caused the failure, or null when none can be identified
     * @param relevant containers to collect logs from, most relevant first
     */
    public record Selection(String primary, List<ContainerSnapshot> relevant) {
    }

    public Selection select(List<ContainerSnapshot> containers, String taskRunReason) {
        ContainerType verdict = typeNamedBy(taskRunReason);
        Comparator<ContainerSnapshot> mostRelevantFirst = Comparator
                .<ContainerSnapshot>comparingInt(c -> c.type() == verdict ? 0 : 1)
                .thenComparingInt(c -> c.type() == ContainerType.INIT ? 0 : 1)
                .thenComparingInt(c -> isOutOfMemory(c) ? 0 : 1)
                .thenComparingInt(c -> c.type() == ContainerType.STEP ? 0 : 1)
                .thenComparingInt(c -> c.ordinal() == null ? Integer.MAX_VALUE : c.ordinal())
                .thenComparing(ContainerSnapshot::name);

        List<ContainerSnapshot> failed = containers.stream().filter(PrimaryFailureSelector::isFailed).toList();
        boolean stepOrInitFailed = failed.stream().anyMatch(c -> c.type() != ContainerType.SIDECAR);
        List<ContainerSnapshot> relevant = failed.stream()
                // Tekton stops sidecars itself after the steps, which usually leaves a non-zero exit code.
                .filter(c -> c.type() != ContainerType.SIDECAR || !stepOrInitFailed
                        || verdict == ContainerType.SIDECAR || isOutOfMemory(c))
                .sorted(mostRelevantFirst)
                .toList();
        if (!relevant.isEmpty()) {
            return new Selection(relevant.getFirst().name(), relevant);
        }
        // Timeouts and cancellations may leave the step running for a moment; its log shows where it was stuck.
        List<ContainerSnapshot> runningSteps = containers.stream()
                .filter(c -> c.type() == ContainerType.STEP && c.state() == ContainerRunState.RUNNING)
                .sorted(mostRelevantFirst)
                .limit(1)
                .toList();
        return new Selection(runningSteps.isEmpty() ? null : runningSteps.getFirst().name(), runningSteps);
    }

    private static boolean isFailed(ContainerSnapshot container) {
        if (container.tektonTerminationReason() != null && NOT_A_CAUSE.contains(container.tektonTerminationReason())) {
            return false;
        }
        return switch (container.state()) {
            case TERMINATED -> isOutOfMemory(container) || (container.exitCode() != null && container.exitCode() != 0);
            case WAITING -> container.reason() != null && START_FAILURES.contains(container.reason());
            case RUNNING, UNKNOWN -> false;
        };
    }

    private static boolean isOutOfMemory(ContainerSnapshot container) {
        return OOM_KILLED.equals(container.reason());
    }

    private static ContainerType typeNamedBy(String taskRunReason) {
        if (taskRunReason == null) {
            return null;
        }
        return switch (taskRunReason) {
            case "StepOOM", "StepFailed" -> ContainerType.STEP;
            case "SidecarOOM", "SidecarFailed" -> ContainerType.SIDECAR;
            case "InitContainerOOM", "InitContainerFailed" -> ContainerType.INIT;
            default -> null;
        };
    }
}
