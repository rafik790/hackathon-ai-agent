package com.hackathon.agent.ai.service;

import com.hackathon.agent.ai.context.FailureContextBuilder;
import com.hackathon.agent.ai.llm.LlmProvider;
import com.hackathon.agent.ai.llm.LlmProviderException;
import com.hackathon.agent.ai.llm.LlmRequest;
import com.hackathon.agent.ai.llm.LlmResponse;
import com.hackathon.agent.ai.llm.LlmUsage;
import com.hackathon.agent.ai.model.AnalysisClaim;
import com.hackathon.agent.ai.model.AnalysisOutcome;
import com.hackathon.agent.ai.model.AnalysisStatus;
import com.hackathon.agent.ai.model.FailureAnalysisResult;
import com.hackathon.agent.ai.model.FailureContext;
import com.hackathon.agent.ai.parser.AnalysisEvidenceValidator;
import com.hackathon.agent.ai.parser.FailureAnalysisParser;
import com.hackathon.agent.ai.parser.InvalidAnalysisException;
import com.hackathon.agent.ai.prompt.FailureAnalysisPromptBuilder;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Processes a single analysis:
 * <ol>
 *   <li>claim it, so that no other worker processes it</li>
 *   <li>build the prompt from the stored evidence</li>
 *   <li>reserve quota and ask the model</li>
 *   <li>check the answer</li>
 *   <li>store the result, or record why it failed</li>
 * </ol>
 * No database transaction is open while the model is asked. If a worker disappears, its claim expires and the
 * analysis is claimed again.
 */
@Component
@ConditionalOnBooleanProperty(name = "ai.enabled")
public class FailureAnalysisProcessor {

    private static final Logger log = LoggerFactory.getLogger(FailureAnalysisProcessor.class);

    private static final String QUOTA_EXHAUSTED = "Waiting: the daily AI quota is exhausted";

    private final AnalysisPersistenceService persistence;
    private final FailureContextBuilder contextBuilder;
    private final FailureAnalysisPromptBuilder promptBuilder;
    private final AiQuotaService quota;
    private final LlmProvider provider;
    private final FailureAnalysisParser parser;
    private final AnalysisEvidenceValidator validator;
    private final ProviderPause pause;
    private volatile boolean stopping;

    public FailureAnalysisProcessor(AnalysisPersistenceService persistence, FailureContextBuilder contextBuilder,
                                    FailureAnalysisPromptBuilder promptBuilder, AiQuotaService quota,
                                    LlmProvider provider, FailureAnalysisParser parser,
                                    AnalysisEvidenceValidator validator, ProviderPause pause) {
        this.persistence = persistence;
        this.contextBuilder = contextBuilder;
        this.promptBuilder = promptBuilder;
        this.quota = quota;
        this.provider = provider;
        this.parser = parser;
        this.validator = validator;
        this.pause = pause;
    }

    public AnalysisOutcome process(Long analysisId) {
        if (stopping || pause.isActive()) {
            return AnalysisOutcome.SKIPPED;
        }
        return persistence.claim(analysisId).map(this::analyse).orElse(AnalysisOutcome.SKIPPED);
    }

    /** From now on, interrupted analyses are given back instead of being recorded as failed. */
    public void stop() {
        stopping = true;
    }

    private AnalysisOutcome analyse(AnalysisClaim claim) {
        LlmResponse response = null;
        try {
            FailureContext context = contextBuilder.build(claim.evidenceId());
            LlmRequest request = promptBuilder.build(context);
            if (!quota.tryReserve(provider, claim.analysisId())) {
                giveBack(claim, QUOTA_EXHAUSTED);
                return AnalysisOutcome.SKIPPED;
            }
            response = provider.generate(request);
            FailureAnalysisResult result = validator.validate(parser.parse(response.text()), context);
            return store(claim, result, response);
        } catch (InvalidAnalysisException e) {
            return recordFailure(claim, usageOf(response), e.getMessage(), true, null);
        } catch (LlmProviderException e) {
            return stopping ? giveBackOnShutdown(claim) : handleProviderError(claim, e);
        } catch (RuntimeException e) {
            return stopping ? giveBackOnShutdown(claim) : handleUnexpectedError(claim, usageOf(response), e);
        }
    }

    private AnalysisOutcome store(AnalysisClaim claim, FailureAnalysisResult result, LlmResponse response) {
        if (!saveResult(claim, result, response)) {
            log.warn("Result of analysis {} discarded: its claim expired", claim.analysisId());
            return AnalysisOutcome.DISCARDED;
        }
        log.info("Analysis {} completed: category={} severity={} confidence={} model={} inputTokens={} outputTokens={}",
                claim.analysisId(), result.failureCategory(), result.severity(), result.confidence(), response.model(),
                response.usage().inputTokens(), response.usage().outputTokens());
        return AnalysisOutcome.COMPLETED;
    }

    /** The answer is already paid for, so a database error is worth a second try. */
    private boolean saveResult(AnalysisClaim claim, FailureAnalysisResult result, LlmResponse response) {
        try {
            return persistence.complete(claim, result, response);
        } catch (DataAccessException e) {
            log.warn("Storing analysis {} failed, trying once more", claim.analysisId(), e);
            return persistence.complete(claim, result, response);
        }
    }

    private AnalysisOutcome handleProviderError(AnalysisClaim claim, LlmProviderException e) {
        pause.startIfNeeded(e);
        return recordFailure(claim, e.usage(), e.category() + ": " + e.getMessage(), e.category().isRetryable(),
                e.retryAfter().orElse(null));
    }

    private AnalysisOutcome handleUnexpectedError(AnalysisClaim claim, LlmUsage usage, RuntimeException e) {
        log.error("Analysis {} failed unexpectedly", claim.analysisId(), e);
        return recordFailure(claim, usage, "Unexpected error: " + e.getClass().getSimpleName(), true, null);
    }

    private AnalysisOutcome recordFailure(AnalysisClaim claim, LlmUsage usage, String error, boolean retryable,
                                          Duration retryAfter) {
        try {
            AnalysisStatus status = persistence.fail(claim, provider.name(), provider.model(), usage, error, retryable,
                    retryAfter).orElse(null);
            log.warn("Analysis {} failed: attempt={} status={} error={}", claim.analysisId(), claim.attempts(), status, error);
            return switch (status) {
                case RETRY_PENDING -> AnalysisOutcome.RETRIED;
                case FAILED -> AnalysisOutcome.FAILED;
                case null, default -> AnalysisOutcome.DISCARDED;
            };
        } catch (RuntimeException e) {
            log.error("Recording the failure of analysis {} failed; it can be claimed again later", claim.analysisId(), e);
            return AnalysisOutcome.DISCARDED;
        }
    }

    private AnalysisOutcome giveBackOnShutdown(AnalysisClaim claim) {
        // The shutdown interrupted this thread, but database access needs an uninterrupted one.
        Thread.interrupted();
        giveBack(claim, null);
        return AnalysisOutcome.DISCARDED;
    }

    private void giveBack(AnalysisClaim claim, String reason) {
        try {
            persistence.release(claim, reason);
        } catch (RuntimeException e) {
            log.warn("Analysis {} could not be given back; it can be claimed again later", claim.analysisId());
        }
    }

    private static LlmUsage usageOf(LlmResponse response) {
        return response == null ? LlmUsage.NONE : response.usage();
    }
}
