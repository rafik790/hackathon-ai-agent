package com.hackathon.agent.investigation.service;

import com.hackathon.agent.ai.llm.LlmUsage;
import com.hackathon.agent.investigation.AgentInvestigationProperties;
import com.hackathon.agent.investigation.entity.AgentInvestigationEntity;
import com.hackathon.agent.investigation.entity.AgentInvestigationStepEntity;
import com.hackathon.agent.investigation.model.AgentReport;
import com.hackathon.agent.investigation.model.InvestigationModel.InvestigationStatus;
import com.hackathon.agent.investigation.repository.AgentInvestigationRepository;
import com.hackathon.agent.investigation.repository.AgentInvestigationStepRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short transactions for running investigations. A worker owns an investigation through its claim token; writes with
 * another token are refused, so two workers never record the same investigation.
 */
@Component
@ConditionalOnBooleanProperty(name = "agent.investigation.enabled")
public class AgentInvestigationStore {

    static final String ABANDONED = "The worker stopped before the investigation finished";
    static final int MAX_ERROR = 4000;

    /** Ownership of a running investigation. */
    public record Claim(Long id, String token) {
    }

    /** The claim expired or was taken over; the worker must stop. */
    public static class LostClaimException extends RuntimeException {

        LostClaimException(Long id) {
            super("Investigation " + id + " is no longer owned by this worker");
        }
    }

    private final AgentInvestigationRepository investigations;
    private final AgentInvestigationStepRepository steps;
    private final AgentInvestigationProperties properties;
    private final Clock clock;

    public AgentInvestigationStore(AgentInvestigationRepository investigations, AgentInvestigationStepRepository steps,
                                   AgentInvestigationProperties properties, Clock clock) {
        this.investigations = investigations;
        this.steps = steps;
        this.properties = properties;
        this.clock = clock;
    }

    /** Fails abandoned investigations, then claims the oldest pending one. */
    @Transactional
    public Optional<Claim> claimNext() {
        Instant now = clock.instant();
        investigations.failAbandoned(now, ABANDONED);
        Optional<AgentInvestigationEntity> next = investigations.findFirstByStatusOrderByIdAsc(InvestigationStatus.PENDING);
        if (next.isEmpty()) {
            return Optional.empty();
        }
        String token = UUID.randomUUID().toString();
        int claimed = investigations.claim(next.get().getId(), token, now.plus(Duration.ofSeconds(properties.leaseSeconds())), now);
        return claimed == 1 ? Optional.of(new Claim(next.get().getId(), token)) : Optional.empty();
    }

    @Transactional(readOnly = true)
    public AgentInvestigationEntity get(Claim claim) {
        return owned(claim);
    }

    /** Stores a step together with the counters and token usage of the investigation. */
    @Transactional
    public void recordStep(Claim claim, AgentInvestigationStepEntity step, int toolCalls, LlmUsage usage, String provider,
                           String model) {
        AgentInvestigationEntity investigation = owned(claim);
        step.setInvestigationId(claim.id());
        step.setCreatedAt(clock.instant());
        steps.save(step);
        investigation.setStepCount(step.getStepNumber());
        investigation.setToolCallCount(toolCalls);
        addUsage(investigation, usage, provider, model);
    }

    @Transactional
    public void complete(Claim claim, AgentReport report) {
        AgentInvestigationEntity investigation = owned(claim);
        investigation.setReport(report);
        investigation.setSummary(report.summary());
        investigation.setRootCause(report.rootCause());
        investigation.setFailureCategory(report.failureCategory());
        investigation.setConfidence(report.confidence());
        finish(investigation, InvestigationStatus.COMPLETED, null);
    }

    @Transactional
    public void fail(Claim claim, String error, LlmUsage usage, String provider, String model) {
        AgentInvestigationEntity investigation = owned(claim);
        addUsage(investigation, usage, provider, model);
        finish(investigation, InvestigationStatus.FAILED, error);
    }

    private void finish(AgentInvestigationEntity investigation, InvestigationStatus status, String error) {
        Instant now = clock.instant();
        investigation.setStatus(status);
        investigation.setErrorMessage(error == null || error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR));
        investigation.setClaimToken(null);
        investigation.setLeaseUntil(null);
        investigation.setCompletedAt(now);
        investigation.setUpdatedAt(now);
    }

    private void addUsage(AgentInvestigationEntity investigation, LlmUsage usage, String provider, String model) {
        if (usage != null) {
            investigation.setInputTokens(investigation.getInputTokens() + usage.inputTokens());
            investigation.setOutputTokens(investigation.getOutputTokens() + usage.outputTokens());
        }
        if (provider != null) {
            investigation.setProvider(provider.length() <= 32 ? provider : provider.substring(0, 32));
        }
        if (model != null) {
            investigation.setModel(model.length() <= 128 ? model : model.substring(0, 128));
        }
        investigation.setUpdatedAt(clock.instant());
    }

    private AgentInvestigationEntity owned(Claim claim) {
        AgentInvestigationEntity investigation = investigations.findById(claim.id()).orElseThrow(() -> new LostClaimException(claim.id()));
        if (investigation.getStatus() != InvestigationStatus.RUNNING || !claim.token().equals(investigation.getClaimToken())) {
            throw new LostClaimException(claim.id());
        }
        return investigation;
    }
}
