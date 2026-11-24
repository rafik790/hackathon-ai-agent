package com.hackathon.agent.ai.entity;

import com.hackathon.agent.ai.model.AnalysisStatus;
import com.hackathon.agent.ai.model.FailureCategory;
import com.hackathon.agent.ai.model.RecommendedAction;
import com.hackathon.agent.ai.model.Severity;
import com.hackathon.agent.ai.model.SupportingEvidence;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * AI-generated analysis of one piece of failure evidence.
 */
@Entity
@Table(name = "failure_analysis")
public class FailureAnalysisEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long failureEvidenceId;

    @Column(nullable = false)
    private int analysisVersion;

    @Column(nullable = false, length = 32)
    private String promptVersion;

    @Column(nullable = false)
    private int revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AnalysisStatus analysisStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private FailureCategory failureCategory;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Severity severity;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(length = 2000)
    private String summary;

    @Column(length = 4000)
    private String rootCause;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> observedFacts;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> hypotheses;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<SupportingEvidence> supportingEvidence;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<RecommendedAction> recommendedActions;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> preventiveRecommendations;

    @Column(length = 32)
    private String provider;

    @Column(length = 128)
    private String model;

    @Column(nullable = false)
    private int inputTokens;

    @Column(nullable = false)
    private int outputTokens;

    @Column(nullable = false)
    private int processingAttempts;

    private Instant nextAttemptAt;

    private Instant startedAt;

    private Instant lockedUntil;

    @Column(length = 36)
    private String claimToken;

    @Column(length = 4000)
    private String lastError;

    private Instant analysedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public Long getFailureEvidenceId() {
        return failureEvidenceId;
    }

    public void setFailureEvidenceId(Long failureEvidenceId) {
        this.failureEvidenceId = failureEvidenceId;
    }

    public int getAnalysisVersion() {
        return analysisVersion;
    }

    public void setAnalysisVersion(int analysisVersion) {
        this.analysisVersion = analysisVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public AnalysisStatus getAnalysisStatus() {
        return analysisStatus;
    }

    public void setAnalysisStatus(AnalysisStatus analysisStatus) {
        this.analysisStatus = analysisStatus;
    }

    public FailureCategory getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(FailureCategory failureCategory) {
        this.failureCategory = failureCategory;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public List<String> getObservedFacts() {
        return observedFacts;
    }

    public void setObservedFacts(List<String> observedFacts) {
        this.observedFacts = observedFacts;
    }

    public List<String> getHypotheses() {
        return hypotheses;
    }

    public void setHypotheses(List<String> hypotheses) {
        this.hypotheses = hypotheses;
    }

    public List<SupportingEvidence> getSupportingEvidence() {
        return supportingEvidence;
    }

    public void setSupportingEvidence(List<SupportingEvidence> supportingEvidence) {
        this.supportingEvidence = supportingEvidence;
    }

    public List<RecommendedAction> getRecommendedActions() {
        return recommendedActions;
    }

    public void setRecommendedActions(List<RecommendedAction> recommendedActions) {
        this.recommendedActions = recommendedActions;
    }

    public List<String> getPreventiveRecommendations() {
        return preventiveRecommendations;
    }

    public void setPreventiveRecommendations(List<String> preventiveRecommendations) {
        this.preventiveRecommendations = preventiveRecommendations;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }

    public int getProcessingAttempts() {
        return processingAttempts;
    }

    public void setProcessingAttempts(int processingAttempts) {
        this.processingAttempts = processingAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public String getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(String claimToken) {
        this.claimToken = claimToken;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getAnalysedAt() {
        return analysedAt;
    }

    public void setAnalysedAt(Instant analysedAt) {
        this.analysedAt = analysedAt;
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
