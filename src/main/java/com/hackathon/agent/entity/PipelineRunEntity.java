package com.hackathon.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipeline_run")
public class PipelineRunEntity extends ExecutionEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String pipelineRunUid;

    @Column(nullable = false, length = 253)
    private String pipelineRunName;

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
}
