package com.hackathon.agent.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Lock row that serializes the quota reservations of one provider.
 */
@Entity
@Table(name = "ai_quota")
public class AiQuotaEntity {

    @Id
    @Column(length = 32)
    private String provider;

    @Column(nullable = false)
    private Instant createdAt;

    protected AiQuotaEntity() {
    }

    public AiQuotaEntity(String provider, Instant createdAt) {
        this.provider = provider;
        this.createdAt = createdAt;
    }

    public String getProvider() {
        return provider;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
