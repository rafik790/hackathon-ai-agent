package com.hackathon.agent.resolution.entity;

import com.hackathon.agent.investigation.model.AgentReport.Team;
import com.hackathon.agent.resolution.model.ResolutionStatus;
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
 * One change of a resolution record, with the values after the change. Append-only: there are no setters for an
 * existing entry and the application never updates or deletes one.
 */
@Entity
@Table(name = "resolution_history")
public class ResolutionHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long resolutionRecordId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, updatable = false)
    private ResolutionStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private ResolutionStatus newStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, updatable = false)
    private Team assignedTeam;

    @Column(length = 128, updatable = false)
    private String assignedPerson;

    @Column(length = 2000, updatable = false)
    private String summary;

    @Column(length = 4000, updatable = false)
    private String note;

    @Column(length = 128, updatable = false)
    private String changedBy;

    @Column(nullable = false, updatable = false)
    private Instant changedAt;

    protected ResolutionHistoryEntity() {
    }

    /** The entry for a change of {@code record}, which already holds the new values. */
    public ResolutionHistoryEntity(ResolutionRecordEntity record, ResolutionStatus previousStatus, String note) {
        this.resolutionRecordId = record.getId();
        this.previousStatus = previousStatus;
        this.newStatus = record.getStatus();
        this.assignedTeam = record.getAssignedTeam();
        this.assignedPerson = record.getAssignedPerson();
        this.summary = record.getSummary();
        this.note = note;
        this.changedBy = record.getUpdatedBy();
        this.changedAt = record.getUpdatedAt();
    }

    public Long getId() {
        return id;
    }

    public Long getResolutionRecordId() {
        return resolutionRecordId;
    }

    public ResolutionStatus getPreviousStatus() {
        return previousStatus;
    }

    public ResolutionStatus getNewStatus() {
        return newStatus;
    }

    public Team getAssignedTeam() {
        return assignedTeam;
    }

    public String getAssignedPerson() {
        return assignedPerson;
    }

    public String getSummary() {
        return summary;
    }

    public String getNote() {
        return note;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
