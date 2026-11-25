package com.hackathon.agent.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One reserved invocation of a provider.
 */
@Entity
@Table(name = "ai_invocation")
public class AiInvocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String provider;

    private Long failureAnalysisId;

    @Column(nullable = false)
    private Instant invokedAt;

    protected AiInvocationEntity() {
    }

    public AiInvocationEntity(String provider, Long failureAnalysisId, Instant invokedAt) {
        this.provider = provider;
        this.failureAnalysisId = failureAnalysisId;
        this.invokedAt = invokedAt;
    }

    public Long getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public Long getFailureAnalysisId() {
        return failureAnalysisId;
    }

    public Instant getInvokedAt() {
        return invokedAt;
    }
}
