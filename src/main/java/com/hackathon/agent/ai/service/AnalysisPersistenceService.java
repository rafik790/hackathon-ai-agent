package com.hackathon.agent.ai.service;

import com.hackathon.agent.ai.entity.FailureAnalysisEntity;
import com.hackathon.agent.ai.llm.LlmProvider;
import com.hackathon.agent.ai.llm.LlmResponse;
import com.hackathon.agent.ai.llm.LlmUsage;
import com.hackathon.agent.ai.model.AnalysisClaim;
import com.hackathon.agent.ai.model.AnalysisStatus;
import com.hackathon.agent.ai.model.FailureAnalysisResult;
import com.hackathon.agent.ai.model.QuotaStatus;
import com.hackathon.agent.ai.prompt.FailureAnalysisPromptBuilder;
import com.hackathon.agent.ai.repository.FailureAnalysisRepository;
import com.hackathon.agent.config.AiProperties;
import com.hackathon.agent.evidence.entity.FailureEvidenceEntity;
import com.hackathon.agent.evidence.model.EvidenceStatus;
import com.hackathon.agent.evidence.repository.FailureEvidenceRepository;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.exception.ConflictException;
import com.hackathon.agent.exception.QuotaExceededException;
import com.hackathon.agent.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database side of failure analysis: short transactions only, no provider calls. Results are only accepted from
 * the worker that still holds the claim.
 */
@Service
public class AnalysisPersistenceService {

    private static final int MAX_ERROR = 4000;

    private final FailureAnalysisRepository repository;
    private final FailureEvidenceRepository evidenceRepository;
    private final AnalysisRetryPolicy retryPolicy;
    private final LogSanitizer sanitizer;
    private final AiQuotaService quota;
    private final ObjectProvider<LlmProvider> provider;
    private final AiProperties properties;
    private final Clock clock;

    public AnalysisPersistenceService(FailureAnalysisRepository repository,
                                      FailureEvidenceRepository evidenceRepository, AnalysisRetryPolicy retryPolicy,
                                      LogSanitizer sanitizer, AiQuotaService quota,
                                      ObjectProvider<LlmProvider> provider, AiProperties properties, Clock clock) {
        this.repository = repository;
        this.evidenceRepository = evidenceRepository;
        this.retryPolicy = retryPolicy;
        this.sanitizer = sanitizer;
        this.quota = quota;
        this.provider = provider;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Long> findEvidenceToAnalyse(int limit) {
        AiProperties.Analysis analysis = properties.analysis();
        return repository.findEvidenceToAnalyse(clock.instant().minus(Duration.ofHours(analysis.lookbackHours())),
                !analysis.reanalyseOnVersionChange(), analysis.version(), FailureAnalysisPromptBuilder.VERSION,
                Limit.of(limit));
    }

    /** Creates the first analysis of the current version; the unique constraint rejects a second one. */
    @Transactional
    public Long enqueue(Long evidenceId) {
        return create(evidenceId, 0).getId();
    }

    @Transactional(readOnly = true)
    public List<Long> findClaimable(int limit) {
        return repository.findClaimable(clock.instant(), Limit.of(limit));
    }

    @Transactional
    public int failAbandoned() {
        return repository.failAbandoned(clock.instant(), properties.analysis().maxAttempts());
    }

    /** @return empty when another worker was faster */
    @Transactional
    public Optional<AnalysisClaim> claim(Long analysisId) {
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();
        int claimed = repository.claim(analysisId, token, now,
                now.plusSeconds(properties.analysis().leaseSeconds()), properties.analysis().maxAttempts());
        if (claimed == 0) {
            return Optional.empty();
        }
        return repository.findByIdAndClaimToken(analysisId, token).map(entity ->
                new AnalysisClaim(entity.getId(), entity.getFailureEvidenceId(), token, entity.getProcessingAttempts()));
    }

    /** @return false when the claim was lost and the result was discarded */
    @Transactional
    public boolean complete(AnalysisClaim claim, FailureAnalysisResult result, LlmResponse response) {
        return update(claim, entity -> {
            entity.setFailureCategory(result.failureCategory());
            entity.setSeverity(result.severity());
            entity.setConfidence(result.confidence());
            entity.setSummary(result.summary());
            entity.setRootCause(result.rootCause());
            entity.setObservedFacts(result.observedFacts());
            entity.setHypotheses(result.hypotheses());
            entity.setSupportingEvidence(result.supportingEvidence());
            entity.setRecommendedActions(result.recommendedActions());
            entity.setPreventiveRecommendations(result.preventiveRecommendations());
            entity.setProvider(limit(response.provider(), 32));
            entity.setModel(limit(response.model(), 128));
            addUsage(entity, response.usage());
            entity.setLastError(null);
            entity.setNextAttemptAt(null);
            entity.setAnalysedAt(clock.instant());
            entity.setAnalysisStatus(AnalysisStatus.COMPLETED);
        });
    }

    /** @return the new status, or empty when the claim was lost */
    @Transactional
    public Optional<AnalysisStatus> fail(AnalysisClaim claim, String provider, String model, LlmUsage usage,
                                         String error, boolean retryable, Duration retryAfter) {
        boolean retry = retryPolicy.mayRetry(retryable, claim.attempts());
        AnalysisStatus status = retry ? AnalysisStatus.RETRY_PENDING : AnalysisStatus.FAILED;
        boolean updated = update(claim, entity -> {
            entity.setProvider(limit(provider, 32));
            entity.setModel(limit(model, 128));
            addUsage(entity, usage);
            entity.setLastError(sanitizer.sanitizeMessage(error, MAX_ERROR));
            entity.setNextAttemptAt(retry ? retryPolicy.nextAttemptAt(claim.attempts(), retryAfter) : null);
            entity.setAnalysisStatus(status);
        });
        return updated ? Optional.of(status) : Optional.empty();
    }

    /**
     * Gives an analysis back without counting the attempt, because it was interrupted by a shutdown or the quota
     * is exhausted. Quota that was already reserved is not given back.
     *
     * @param reason shown to users while the analysis is waiting, or null
     */
    @Transactional
    public void release(AnalysisClaim claim, String reason) {
        update(claim, entity -> {
            entity.setProcessingAttempts(Math.max(0, entity.getProcessingAttempts() - 1));
            if (reason != null) {
                entity.setLastError(reason);
            }
            entity.setNextAttemptAt(null);
            entity.setAnalysisStatus(AnalysisStatus.PENDING);
        });
    }

    /**
     * Requests an analysis manually. An analysis already in progress is returned instead of starting another one.
     *
     * @param force whether to analyse evidence again that already has a completed analysis of the current version
     */
    @Transactional
    public Long request(Long evidenceId, boolean force) {
        if (!properties.enabled()) {
            throw new ConflictException("AI analysis is disabled");
        }
        FailureEvidenceEntity evidence = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new ResourceNotFoundException("FailureEvidence", evidenceId));
        if (evidence.getStatus() == EvidenceStatus.PENDING) {
            throw new ConflictException("Evidence collection is not finished yet");
        }
        List<FailureAnalysisEntity> analyses = repository.findByFailureEvidenceIdOrderByIdDesc(evidenceId);
        Optional<FailureAnalysisEntity> inProgress = analyses.stream()
                .filter(a -> a.getAnalysisStatus().isInProgress()).findFirst();
        if (inProgress.isPresent()) {
            return inProgress.get().getId();
        }
        List<FailureAnalysisEntity> current = analyses.stream()
                .filter(a -> a.getAnalysisVersion() == properties.analysis().version()
                        && FailureAnalysisPromptBuilder.VERSION.equals(a.getPromptVersion()))
                .toList();
        current.stream().filter(a -> a.getAnalysisStatus() == AnalysisStatus.COMPLETED).findFirst().ifPresent(a -> {
            if (!force) {
                throw new ConflictException("Evidence %d was already analysed (analysis %d); use force=true to analyse it again"
                        .formatted(evidenceId, a.getId()));
            }
        });
        // Checked again, atomically, before the provider is invoked.
        provider.ifAvailable(this::requireQuota);
        int revision = current.stream().mapToInt(FailureAnalysisEntity::getRevision).max().orElse(-1) + 1;
        return create(evidenceId, revision).getId();
    }

    private void requireQuota(LlmProvider llmProvider) {
        QuotaStatus status = quota.status(llmProvider);
        if (status.isExhausted()) {
            throw new QuotaExceededException("The daily AI quota of %d invocations is exhausted; it becomes available again at %s"
                    .formatted(status.limit(), status.availableAt()), status.availableAt());
        }
    }

    private FailureAnalysisEntity create(Long evidenceId, int revision) {
        Instant now = clock.instant();
        FailureAnalysisEntity entity = new FailureAnalysisEntity();
        entity.setFailureEvidenceId(evidenceId);
        entity.setAnalysisVersion(properties.analysis().version());
        entity.setPromptVersion(FailureAnalysisPromptBuilder.VERSION);
        entity.setRevision(revision);
        entity.setAnalysisStatus(AnalysisStatus.PENDING);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return repository.saveAndFlush(entity);
    }

    private boolean update(AnalysisClaim claim, Consumer<FailureAnalysisEntity> change) {
        FailureAnalysisEntity entity = repository.findByIdAndClaimToken(claim.analysisId(), claim.token()).orElse(null);
        if (entity == null || entity.getAnalysisStatus() != AnalysisStatus.PROCESSING) {
            return false;
        }
        change.accept(entity);
        entity.setClaimToken(null);
        entity.setLockedUntil(null);
        entity.setUpdatedAt(clock.instant());
        return true;
    }

    private static void addUsage(FailureAnalysisEntity entity, LlmUsage usage) {
        entity.setInputTokens(entity.getInputTokens() + usage.inputTokens());
        entity.setOutputTokens(entity.getOutputTokens() + usage.outputTokens());
    }

    private static String limit(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
