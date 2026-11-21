package com.hackathon.agent.support;

import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import java.time.Instant;

public final class TestEntities {

    public static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private TestEntities() {
    }

    public static PipelineRunEntity pipelineRun(String uid, String namespace, String pipeline, ExecutionStatus status,
                                                Instant start, Long durationMs) {
        PipelineRunEntity e = new PipelineRunEntity();
        e.setPipelineRunUid(uid);
        e.setPipelineRunName(pipeline + "-" + uid);
        e.setNamespace(namespace);
        e.setPipelineName(pipeline);
        e.setStatus(status);
        e.setStartTime(start);
        if (durationMs != null && start != null) {
            e.setCompletionTime(start.plusMillis(durationMs));
        }
        e.setDurationMs(durationMs);
        if (status == ExecutionStatus.FAILED) {
            e.setFailureReason("Failed");
            e.setFailureMessage("Tasks Completed: 2 (Failed: 1)");
        }
        e.setCreatedAt(NOW);
        e.setUpdatedAt(NOW);
        return e;
    }

    public static TaskRunEntity taskRun(String uid, PipelineRunEntity parent, String task, ExecutionStatus status,
                                        Instant start, Long durationMs) {
        TaskRunEntity e = new TaskRunEntity();
        e.setTaskRunUid(uid);
        e.setTaskRunName(parent.getPipelineRunName() + "-" + task);
        e.setNamespace(parent.getNamespace());
        e.setPipelineRunUid(parent.getPipelineRunUid());
        e.setPipelineRunName(parent.getPipelineRunName());
        e.setPipelineName(parent.getPipelineName());
        e.setTaskName(task);
        e.setStatus(status);
        e.setStartTime(start);
        e.setDurationMs(durationMs);
        if (durationMs != null && start != null) {
            e.setCompletionTime(start.plusMillis(durationMs));
        }
        e.setCreatedAt(NOW);
        e.setUpdatedAt(NOW);
        return e;
    }
}
