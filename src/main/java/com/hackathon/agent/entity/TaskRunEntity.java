package com.hackathon.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "task_run")
public class TaskRunEntity extends ExecutionEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String taskRunUid;

    @Column(nullable = false, length = 253)
    private String taskRunName;

    /** UID of the owning PipelineRun (from ownerReferences); null for standalone TaskRuns. */
    @Column(length = 64)
    private String pipelineRunUid;

    @Column(length = 253)
    private String pipelineRunName;

    @Column(length = 253)
    private String taskName;

    public String getTaskRunUid() {
        return taskRunUid;
    }

    public void setTaskRunUid(String taskRunUid) {
        this.taskRunUid = taskRunUid;
    }

    public String getTaskRunName() {
        return taskRunName;
    }

    public void setTaskRunName(String taskRunName) {
        this.taskRunName = taskRunName;
    }

    public String getPipelineRunUid() {
        return pipelineRunUid;
    }

    public void setPipelineRunUid(String pipelineRunUid) {
        this.pipelineRunUid = pipelineRunUid;
    }

    public String getPipelineRunName() {
        return pipelineRunName;
    }

    public void setPipelineRunName(String pipelineRunName) {
        this.pipelineRunName = pipelineRunName;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }
}
