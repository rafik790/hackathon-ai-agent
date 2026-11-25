package com.hackathon.agent.ai.service;

import com.hackathon.agent.ai.entity.AiInvocationEntity;
import com.hackathon.agent.ai.entity.AiQuotaEntity;
import com.hackathon.agent.ai.llm.LlmProvider;
import com.hackathon.agent.ai.model.QuotaStatus;
import com.hackathon.agent.ai.repository.AiInvocationRepository;
import com.hackathon.agent.ai.repository.AiQuotaRepository;
import com.hackathon.agent.config.AiProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Limits provider invocations to {@code ai.analysis.daily-limit} within any 24 hours, per provider and across all
 * workers and instances.
 * <p>
 * A reservation is committed in its own short transaction before the provider is called and is never given back:
 * an invocation that fails, times out or is interrupted may have been billed. Providers without costs are exempt.
 */
@Service
public class AiQuotaService {

    private static final Duration WINDOW = Duration.ofHours(24);

    private final AiQuotaRepository quotas;
    private final AiInvocationRepository invocations;
    private final AiProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AiQuotaService(AiQuotaRepository quotas, AiInvocationRepository invocations, AiProperties properties,
                          Clock clock, PlatformTransactionManager transactionManager) {
        this.quotas = quotas;
        this.invocations = invocations;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Reserves one invocation. Must be called directly before every invocation.
     *
     * @return false when the quota is exhausted; the provider must not be called then
     */
    public boolean tryReserve(LlmProvider provider, Long analysisId) {
        if (!provider.consumesQuota()) {
            return true;
        }
        String name = provider.name();
        ensureLockRow(name);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            quotas.lock(name).orElseThrow(() -> new IllegalStateException("No quota row for provider " + name));
            Instant now = clock.instant();
            if (invocations.countByProviderAndInvokedAtGreaterThan(name, now.minus(WINDOW))
                    >= properties.analysis().dailyLimit()) {
                return false;
            }
            invocations.save(new AiInvocationEntity(name, analysisId, now));
            return true;
        }));
    }

    /** Current usage, for information only; {@link #tryReserve} decides. */
    public QuotaStatus status(LlmProvider provider) {
        if (!provider.consumesQuota()) {
            return QuotaStatus.UNLIMITED;
        }
        Instant since = clock.instant().minus(WINDOW);
        int limit = properties.analysis().dailyLimit();
        int used = (int) invocations.countByProviderAndInvokedAtGreaterThan(provider.name(), since);
        Instant oldest = used >= limit ? invocations.findOldest(provider.name(), since) : null;
        return new QuotaStatus(true, used, limit, oldest == null ? null : oldest.plus(WINDOW));
    }

    private void ensureLockRow(String provider) {
        if (quotas.existsById(provider)) {
            return;
        }
        try {
            transaction.executeWithoutResult(status -> quotas.saveAndFlush(new AiQuotaEntity(provider, clock.instant())));
        } catch (DataIntegrityViolationException e) {
            // Another worker created it at the same time.
        }
    }
}
