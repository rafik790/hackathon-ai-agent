package com.hackathon.agent.ai.service;

import com.hackathon.agent.ai.context.FailureContextBuilder;
import com.hackathon.agent.ai.llm.LlmProvider;
import com.hackathon.agent.ai.llm.LlmProviderException;
import com.hackathon.agent.ai.llm.LlmResponse;
import com.hackathon.agent.ai.llm.LlmUsage;
import com.hackathon.agent.ai.model.AnalysisClaim;
import com.hackathon.agent.ai.model.AnalysisCycleReport;
import com.hackathon.agent.ai.model.AnalysisStatus;
import com.hackathon.agent.ai.model.FailureAnalysisResult;
import com.hackathon.agent.ai.model.FailureContext;
import com.hackathon.agent.ai.parser.FailureAnalysisParser;
import com.hackathon.agent.ai.parser.InvalidAnalysisException;
import com.hackathon.agent.ai.prompt.FailureAnalysisPromptBuilder;
import com.hackathon.agent.config.AiProperties;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Analyses failure evidence with the configured provider.
 * <p>
 * Work is claimed and results are stored in short transactions; no transaction is open while the provider is
 * called. A worker that disappears loses its claim after the lease, and its analysis is claimed again.
 */
@Service
@ConditionalOnBooleanProperty(name = "ai.enabled")
public class FailureAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(FailureAnalysisService.class);

    /** Pauses after errors that would affect every request, unless the provider asks for a longer one. */
    private static final Duration RATE_LIMIT_PAUSE = Duration.ofMinutes(1);
    private static final Duration ACCOUNT_PAUSE = Duration.ofMinutes(15);

    private final AnalysisPersistenceService persistence;
    private final FailureContextBuilder contextBuilder;
    private final FailureAnalysisPromptBuilder promptBuilder;
    private final LlmProvider provider;
    private final FailureAnalysisParser parser;
    private final AnalysisStatusTracker tracker;
    private final AiProperties properties;
    private final Clock clock;
    private final ReentrantLock cycleLock = new ReentrantLock();
    // Not a Spring bean: an executor bean would replace the one used for monitoring.
    private final ThreadPoolExecutor workers;
    private volatile Instant pausedUntil = Instant.MIN;
    private volatile boolean stopping;

    public FailureAnalysisService(AnalysisPersistenceService persistence, FailureContextBuilder contextBuilder,
                                  FailureAnalysisPromptBuilder promptBuilder, LlmProvider provider,
                                  FailureAnalysisParser parser, AnalysisStatusTracker tracker,
                                  AiProperties properties, Clock clock) {
        this.persistence = persistence;
        this.contextBuilder = contextBuilder;
        this.promptBuilder = promptBuilder;
        this.provider = provider;
        this.parser = parser;
        this.tracker = tracker;
        this.properties = properties;
        this.clock = clock;
        int concurrency = properties.analysis().maxConcurrentRequests();
        this.workers = new ThreadPoolExecutor(concurrency, concurrency, 1, TimeUnit.MINUTES,
                new ArrayBlockingQueue<>(properties.analysis().batchSize()),
                Thread.ofVirtual().name("ai-analysis-", 0).factory());
    }

    /** @return the cycle report, or empty when a cycle was already running */
    public Optional<AnalysisCycleReport> runCycle() {
        if (!cycleLock.tryLock()) {
            log.warn("Analysis cycle skipped: previous cycle still running");
            return Optional.empty();
        }
        try {
            AnalysisCycleReport report = executeCycle();
            tracker.record(report);
            return Optional.of(report);
        } finally {
            cycleLock.unlock();
        }
    }

    private AnalysisCycleReport executeCycle() {
        Instant startedAt = clock.instant();
        Counters counters = new Counters();
        int enqueued = 0;
        boolean paused = isPaused();
        if (!paused) {
            int abandoned = persistence.failAbandoned();
            if (abandoned > 0) {
                log.warn("Gave up {} analyses without attempts left", abandoned);
            }
            int capacity = Math.min(properties.analysis().batchSize(), persistence.remainingToday());
            List<Long> claimable = persistence.findClaimable(capacity == 0 ? 1 : capacity);
            if (capacity == 0) {
                if (!claimable.isEmpty()) {
                    log.warn("Daily limit of {} analyses reached", properties.analysis().dailyLimit());
                }
            } else {
                if (claimable.size() < capacity) {
                    enqueued = enqueueNew(capacity - claimable.size());
                    claimable = persistence.findClaimable(capacity);
                }
                processAll(claimable, counters);
            }
        }
        Instant finishedAt = clock.instant();
        AnalysisCycleReport report = new AnalysisCycleReport(finishedAt, Duration.between(startedAt, finishedAt).toMillis(),
                enqueued, counters.processed.get(), counters.completed.get(), counters.retried.get(),
                counters.failed.get(), paused || isPaused());
        if (report.processed() > 0) {
            log.info("Analysis cycle completed: durationMs={} enqueued={} processed={} completed={} retried={} failed={} paused={}",
                    report.durationMs(), report.enqueued(), report.processed(), report.completed(), report.retried(),
                    report.failed(), report.paused());
        }
        return report;
    }

    private int enqueueNew(int limit) {
        int enqueued = 0;
        for (Long evidenceId : persistence.findEvidenceToAnalyse(limit)) {
            try {
                persistence.enqueue(evidenceId);
                enqueued++;
            } catch (DataIntegrityViolationException e) {
                log.debug("Evidence {} was enqueued by another instance", evidenceId);
            }
        }
        return enqueued;
    }

    private void processAll(List<Long> analysisIds, Counters counters) {
        List<Future<?>> futures = new ArrayList<>();
        for (Long id : analysisIds) {
            futures.add(workers.submit(() -> process(id, counters)));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                log.error("Analysis worker failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void process(Long analysisId, Counters counters) {
        // Claimed only now, so that waiting in the queue does not use up the lease.
        if (stopping || isPaused()) {
            return;
        }
        AnalysisClaim claim = persistence.claim(analysisId).orElse(null);
        if (claim == null) {
            return;
        }
        counters.processed.incrementAndGet();
        LlmResponse response = null;
        try {
            FailureContext context = contextBuilder.build(claim.evidenceId());
            response = provider.generate(promptBuilder.build(context));
            FailureAnalysisResult result = parser.parse(response.text(), context);
            if (complete(claim, result, response)) {
                counters.completed.incrementAndGet();
                log.info("Analysis {} completed: category={} severity={} confidence={} model={} inputTokens={} outputTokens={}",
                        analysisId, result.failureCategory(), result.severity(), result.confidence(), response.model(),
                        response.usage().inputTokens(), response.usage().outputTokens());
            } else {
                log.warn("Result of analysis {} discarded: its claim expired", analysisId);
            }
        } catch (LlmProviderException e) {
            if (stopping) {
                release(claim);
                return;
            }
            pauseIfNeeded(e);
            fail(claim, e.usage(), e.category() + ": " + e.getMessage(), e.category().isRetryable(),
                    e.retryAfter().orElse(null), counters);
        } catch (InvalidAnalysisException e) {
            fail(claim, response.usage(), e.getMessage(), true, null, counters);
        } catch (RuntimeException e) {
            if (stopping) {
                release(claim);
                return;
            }
            log.error("Analysis {} failed unexpectedly", analysisId, e);
            fail(claim, response == null ? LlmUsage.NONE : response.usage(),
                    "Unexpected error: " + e.getClass().getSimpleName(), true, null, counters);
        }
    }

    /** The answer is paid for, so storing it is tried twice before the analysis has to be repeated. */
    private boolean complete(AnalysisClaim claim, FailureAnalysisResult result, LlmResponse response) {
        try {
            return persistence.complete(claim, result, response);
        } catch (DataAccessException e) {
            log.warn("Storing analysis {} failed, trying once more", claim.analysisId(), e);
            return persistence.complete(claim, result, response);
        }
    }

    private void fail(AnalysisClaim claim, LlmUsage usage, String error, boolean retryable, Duration retryAfter,
                      Counters counters) {
        try {
            AnalysisStatus status = persistence.fail(claim, provider.name(), provider.model(), usage, error,
                    retryable, retryAfter).orElse(null);
            log.warn("Analysis {} failed: attempt={} status={} error={}", claim.analysisId(), claim.attempts(), status, error);
            if (status == AnalysisStatus.RETRY_PENDING) {
                counters.retried.incrementAndGet();
            } else if (status == AnalysisStatus.FAILED) {
                counters.failed.incrementAndGet();
            }
        } catch (RuntimeException e) {
            // The lease makes the analysis available again.
            log.error("Recording the failure of analysis {} failed", claim.analysisId(), e);
        }
    }

    private void release(AnalysisClaim claim) {
        try {
            // Database access needs an uninterrupted thread.
            Thread.interrupted();
            persistence.release(claim);
        } catch (RuntimeException e) {
            log.warn("Analysis {} could not be released; its lease makes it available again", claim.analysisId());
        }
    }

    private void pauseIfNeeded(LlmProviderException e) {
        if (!e.category().affectsAllRequests()) {
            return;
        }
        Duration pause = e.category() == LlmProviderException.Category.RATE_LIMITED ? RATE_LIMIT_PAUSE : ACCOUNT_PAUSE;
        if (e.retryAfter().isPresent() && e.retryAfter().get().compareTo(pause) > 0) {
            pause = e.retryAfter().get();
        }
        Instant until = clock.instant().plus(pause);
        synchronized (this) {
            if (until.isAfter(pausedUntil)) {
                pausedUntil = until;
            }
        }
        log.warn("Analysis paused until {} after {}", pausedUntil, e.category());
    }

    private boolean isPaused() {
        return clock.instant().isBefore(pausedUntil);
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Analyses did not stop in time; their leases make them available again");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Counters {
        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger retried = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
    }
}
