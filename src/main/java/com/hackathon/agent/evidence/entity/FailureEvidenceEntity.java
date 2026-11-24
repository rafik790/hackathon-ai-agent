package com.hackathon.agent.evidence.entity;

import com.hackathon.agent.evidence.model.EvidenceStatus;
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
 * Evidence of one failed TaskRun attempt.
 */
@Entity
@Table(name = "failure_evidence")
public class FailureEvidenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long taskRunId;

    @Column(nullable = false)
    private int attempt;

    @Column(length = 253)
    private String podName;

    @Column(length = 64)
    private String podUid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EvidenceStatus status;

    @Column(length = 16)
    private String podPhase;

    @Column(length = 255)
    private String podReason;

    @Column(length = 4000)
    private String podMessage;

    @Column(nullable = false)
    private int fetchAttempts;

    private Instant nextAttemptAt;

    @Column(length = 4000)
    private String lastError;

    private Instant collectedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public Long getTaskRunId() {
        return taskRunId;
    }

    public void setTaskRunId(Long taskRunId) {
        this.taskRunId = taskRunId;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public String getPodName() {
        return podName;
    }

    public void setPodName(String podName) {
        this.podName = podName;
    }

    public String getPodUid() {
        return podUid;
    }

    public void setPodUid(String podUid) {
        this.podUid = podUid;
    }

    public EvidenceStatus getStatus() {
        return status;
    }

    public void setStatus(EvidenceStatus status) {
        this.status = status;
    }

    public String getPodPhase() {
        return podPhase;
    }

    public void setPodPhase(String podPhase) {
        this.podPhase = podPhase;
    }

    public String getPodReason() {
        return podReason;
    }

    public void setPodReason(String podReason) {
        this.podReason = podReason;
    }

    public String getPodMessage() {
        return podMessage;
    }

    public void setPodMessage(String podMessage) {
        this.podMessage = podMessage;
    }

    public int getFetchAttempts() {
        return fetchAttempts;
    }

    public void setFetchAttempts(int fetchAttempts) {
        this.fetchAttempts = fetchAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(Instant collectedAt) {
        this.collectedAt = collectedAt;
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
