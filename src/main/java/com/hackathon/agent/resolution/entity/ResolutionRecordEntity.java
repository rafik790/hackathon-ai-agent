package com.hackathon.agent.resolution.entity;

import com.hackathon.agent.investigation.model.AgentReport.Team;
import com.hackathon.agent.resolution.model.ResolutionStatus;
import com.hackathon.agent.resolution.model.ResolutionTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Resolution of one failed TaskRun or PipelineRun (V6). Created on the first update; until then a failed run is OPEN.
 */
@Entity
@Table(name = "resolution_record")
public class ResolutionRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ResolutionTargetType targetType;

    @Column(nullable = false, length = 48, unique = true)
    private String targetKey;

    private Long taskRunId;

    private Long pipelineRunId;

    @Column(nullable = false, length = 253)
    private String targetName;

    @Column(nullable = false, length = 63)
    private String namespace;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ResolutionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Team assignedTeam;

    @Column(length = 128)
    private String assignedPerson;

    @Column(length = 2000)
    private String summary;

    @Column(length = 4000)
    private String notes;

    @Column(length = 128)
    private String updatedBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant resolvedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public Long getId() {
        return id;
    }

    public ResolutionTargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(ResolutionTargetType targetType) {
        this.targetType = targetType;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public void setTargetKey(String targetKey) {
        this.targetKey = targetKey;
    }

    public Long getTaskRunId() {
        return taskRunId;
    }

    public void setTaskRunId(Long taskRunId) {
        this.taskRunId = taskRunId;
    }

    public Long getPipelineRunId() {
        return pipelineRunId;
    }

    public void setPipelineRunId(Long pipelineRunId) {
        this.pipelineRunId = pipelineRunId;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public ResolutionStatus getStatus() {
        return status;
    }

    public void setStatus(ResolutionStatus status) {
        this.status = status;
    }

    public Team getAssignedTeam() {
        return assignedTeam;
    }

    public void setAssignedTeam(Team assignedTeam) {
        this.assignedTeam = assignedTeam;
    }

    public String getAssignedPerson() {
        return assignedPerson;
    }

    public void setAssignedPerson(String assignedPerson) {
        this.assignedPerson = assignedPerson;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public long getVersion() {
        return version;
    }
}
