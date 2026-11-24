package com.hackathon.agent.evidence.entity;

import com.hackathon.agent.evidence.model.ContainerRunState;
import com.hackathon.agent.evidence.model.ContainerType;
import com.hackathon.agent.evidence.model.ExitCodeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * State of one container of a failed TaskRun attempt.
 */
@Entity
@Table(name = "container_evidence")
public class ContainerEvidenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long evidenceId;

    @Column(nullable = false, length = 253)
    private String containerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ContainerType containerType;

    @Column(length = 253)
    private String stepName;

    private Integer ordinal;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ContainerRunState state;

    private Integer exitCode;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ExitCodeSource exitCodeSource;

    private Integer signal;

    @Column(length = 255)
    private String reason;

    @Column(length = 255)
    private String tektonTerminationReason;

    @Column(length = 4000)
    private String message;

    private Integer restartCount;

    private Instant startedAt;

    private Instant finishedAt;

    @Column(nullable = false)
    private boolean primaryFailure;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public Long getEvidenceId() {
        return evidenceId;
    }

    public void setEvidenceId(Long evidenceId) {
        this.evidenceId = evidenceId;
    }

    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    public ContainerType getContainerType() {
        return containerType;
    }

    public void setContainerType(ContainerType containerType) {
        this.containerType = containerType;
    }

    public String getStepName() {
        return stepName;
    }

    public void setStepName(String stepName) {
        this.stepName = stepName;
    }

    public Integer getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(Integer ordinal) {
        this.ordinal = ordinal;
    }

    public ContainerRunState getState() {
        return state;
    }

    public void setState(ContainerRunState state) {
        this.state = state;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public ExitCodeSource getExitCodeSource() {
        return exitCodeSource;
    }

    public void setExitCodeSource(ExitCodeSource exitCodeSource) {
        this.exitCodeSource = exitCodeSource;
    }

    public Integer getSignal() {
        return signal;
    }

    public void setSignal(Integer signal) {
        this.signal = signal;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getTektonTerminationReason() {
        return tektonTerminationReason;
    }

    public void setTektonTerminationReason(String tektonTerminationReason) {
        this.tektonTerminationReason = tektonTerminationReason;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Integer getRestartCount() {
        return restartCount;
    }

    public void setRestartCount(Integer restartCount) {
        this.restartCount = restartCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public boolean isPrimaryFailure() {
        return primaryFailure;
    }

    public void setPrimaryFailure(boolean primaryFailure) {
        this.primaryFailure = primaryFailure;
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
}
