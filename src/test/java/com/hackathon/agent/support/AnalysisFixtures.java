package com.hackathon.agent.support;

import com.hackathon.agent.ai.model.FailureContext;
import com.hackathon.agent.config.AiProperties;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.evidence.model.ContainerRunState;
import com.hackathon.agent.evidence.model.ContainerType;
import com.hackathon.agent.evidence.model.EvidenceStatus;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import com.hackathon.agent.evidence.model.LogStatus;
import java.util.List;

public final class AnalysisFixtures {

    public static final String VALID_ANSWER = """
            {
              "summary": "Pushing the image failed.",
              "failureCategory": "REGISTRY_AUTHENTICATION",
              "rootCause": "The registry rejected the credentials.",
              "severity": "HIGH",
              "confidence": 0.92,
              "observedFacts": ["step-test exited with code 2"],
              "hypotheses": ["The credential may have expired"],
              "supportingEvidence": [
                {"source": "CONTAINER_STATUS", "containerName": "step-test", "observation": "exitCode 2"},
                {"source": "TASK_RUN", "containerName": null, "observation": "denied: authentication required"}
              ],
              "recommendedActions": [{"priority": "HIGH", "action": "Renew the credential", "reason": "It was rejected"}],
              "preventiveRecommendations": ["Check credentials before the build"]
            }
            """;

    private AnalysisFixtures() {
    }

    public static AiProperties properties() {
        return new AiProperties(true, "mock",
                new AiProperties.Analysis(1, false, 24, 100, 5, 30000, 0, 2, 3, 60, 600, 60000));
    }

    public static FailureContext context(String log) {
        return new FailureContext(null,
                new FailureContext.TaskRun("build", "uid-build", "build", "ci", ExecutionStatus.FAILED, null, null,
                        "Failed", "denied: authentication required", 0, 1),
                EvidenceStatus.COLLECTED,
                new FailureContext.Pod("build-pod", "pod-uid", "Failed", null, null),
                List.of(new FailureContext.Container("step-test", ContainerType.STEP, "test", 0,
                        ContainerRunState.TERMINATED, 2, ExitCodeSource.POD, null, "Error", "Error", null, 0, true)),
                List.of(new FailureContext.ContainerLog("step-test", false, LogStatus.COLLECTED, false, log)),
                List.of(), List.of());
    }
}
