package com.hackathon.agent.evidence.entity;

import com.hackathon.agent.evidence.model.LogStatus;
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
 * Sanitized log of the current or previous instance of a container.
 */
@Entity
@Table(name = "container_log")
public class ContainerLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long containerEvidenceId;

    @Column(nullable = false)
    private boolean previous;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LogStatus status;

    @Column(length = 65536)
    private String content;

    private Integer storedBytes;

    @Column(nullable = false)
    private boolean truncated;

    @Column(length = 64)
    private String sha256;

    @Column(nullable = false)
    private int redactions;

    private Instant fetchedAt;

    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public Long getContainerEvidenceId() {
        return containerEvidenceId;
    }

    public void setContainerEvidenceId(Long containerEvidenceId) {
        this.containerEvidenceId = containerEvidenceId;
    }

    public boolean isPrevious() {
        return previous;
    }

    public void setPrevious(boolean previous) {
        this.previous = previous;
    }

    public LogStatus getStatus() {
        return status;
    }

    public void setStatus(LogStatus status) {
        this.status = status;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getStoredBytes() {
        return storedBytes;
    }

    public void setStoredBytes(Integer storedBytes) {
        this.storedBytes = storedBytes;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public int getRedactions() {
        return redactions;
    }

    public void setRedactions(int redactions) {
        this.redactions = redactions;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
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
