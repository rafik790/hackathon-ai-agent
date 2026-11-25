package com.hackathon.agent.ai.service;

import com.hackathon.agent.ai.llm.LlmProvider;
import com.hackathon.agent.ai.model.AnalysisCycleReport;
import com.hackathon.agent.ai.model.AnalysisOutcome;
import com.hackathon.agent.config.AiProperties;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Runs analysis cycles. A cycle selects waiting analyses, adds new ones for recent failures, and lets a few workers
 * process them in parallel. How a single analysis is processed is described in {@link FailureAnalysisProcessor}.
 */
@Service
@ConditionalOnBooleanProperty(name = "ai.enabled")
public class FailureAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(FailureAnalysisService.class);

    private final AnalysisPersistenceService persistence;
    private final FailureAnalysisProcessor processor;
    private final AiQuotaService quota;
    private final LlmProvider provider;
    private final ProviderPause pause;
    private final AnalysisStatusTracker tracker;
    private final AiProperties.Analysis settings;
    private final Clock clock;
    private final ReentrantLock cycleLock = new ReentrantLock();
    private final ThreadPoolExecutor workers;

    public FailureAnalysisService(AnalysisPersistenceService persistence, FailureAnalysisProcessor processor,
                                  AiQuotaService quota, LlmProvider provider, ProviderPause pause,
                                  AnalysisStatusTracker tracker, AiProperties properties, Clock clock) {
        this.persistence = persistence;
        this.processor = processor;
        this.quota = quota;
        this.provider = provider;
        this.pause = pause;
        this.tracker = tracker;
        this.settings = properties.analysis();
        this.clock = clock;
        this.workers = createWorkers(settings);
    }

    /** Not a Spring bean, because an executor bean would replace the one used for monitoring. */
    private static ThreadPoolExecutor createWorkers(AiProperties.Analysis settings) {
        return new ThreadPoolExecutor(settings.maxConcurrentRequests(), settings.maxConcurrentRequests(),
                1, TimeUnit.MINUTES, new ArrayBlockingQueue<>(settings.batchSize()),
                Thread.ofVirtual().name("ai-analysis-", 0).factory());
    }

    /** @return the cycle report, or empty when a cycle was already running */
    public Optional<AnalysisCycleReport> runCycle() {
        if (!cycleLock.tryLock()) {
            log.warn("Analysis cycle skipped: previous cycle still running");
            return Optional.empty();
        }
        try {
            return Optional.of(executeCycle());
        } finally {
            cycleLock.unlock();
        }
    }

    private AnalysisCycleReport executeCycle() {
        Instant startedAt = clock.instant();
        boolean paused = pause.isActive();
        int enqueued = 0;
        List<AnalysisOutcome> outcomes = List.of();
        if (!paused) {
            giveUpAbandoned();
            int capacity = capacity();
            enqueued = enqueueNew(capacity);
            outcomes = processAll(capacity);
        }
        AnalysisCycleReport report = createReport(startedAt, enqueued, outcomes, paused || pause.isActive());
        logReport(report);
        tracker.record(report);
        return report;
    }

    private void giveUpAbandoned() {
        int abandoned = persistence.failAbandoned();
        if (abandoned > 0) {
            log.warn("Gave up {} analyses without attempts left", abandoned);
        }
    }

    /** Number of analyses worth starting now. The quota itself is enforced when the model is asked. */
    private int capacity() {
        return Math.min(settings.batchSize(), quota.status(provider).remaining());
    }

    /** Fills the free capacity with failures that were not analysed yet. */
    private int enqueueNew(int capacity) {
        if (capacity == 0) {
            return 0;
        }
        int free = capacity - persistence.findClaimable(capacity).size();
        if (free <= 0) {
            return 0;
        }
        int enqueued = 0;
        for (Long evidenceId : persistence.findEvidenceToAnalyse(free)) {
            try {
                persistence.enqueue(evidenceId);
                enqueued++;
            } catch (DataIntegrityViolationException e) {
                log.debug("Evidence {} was enqueued by another instance", evidenceId);
            }
        }
        return enqueued;
    }

    private List<AnalysisOutcome> processAll(int capacity) {
        if (capacity == 0) {
            return List.of();
        }
        List<Future<AnalysisOutcome>> futures = new ArrayList<>();
        for (Long analysisId : persistence.findClaimable(capacity)) {
            futures.add(workers.submit(() -> processor.process(analysisId)));
        }
        List<AnalysisOutcome> outcomes = new ArrayList<>();
        for (Future<AnalysisOutcome> future : futures) {
            try {
                outcomes.add(future.get());
            } catch (ExecutionException e) {
                log.error("Analysis worker failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return outcomes;
    }

    private AnalysisCycleReport createReport(Instant startedAt, int enqueued, List<AnalysisOutcome> outcomes,
                                             boolean paused) {
        Instant finishedAt = clock.instant();
        return new AnalysisCycleReport(
                finishedAt,
                Duration.between(startedAt, finishedAt).toMillis(),
                enqueued,
                outcomes.size() - count(outcomes, AnalysisOutcome.SKIPPED),
                count(outcomes, AnalysisOutcome.COMPLETED),
                count(outcomes, AnalysisOutcome.RETRIED),
                count(outcomes, AnalysisOutcome.FAILED),
                paused,
                quota.status(provider).isExhausted());
    }

    private static int count(List<AnalysisOutcome> outcomes, AnalysisOutcome outcome) {
        return Collections.frequency(outcomes, outcome);
    }

    private void logReport(AnalysisCycleReport report) {
        if (report.processed() > 0) {
            log.info("Analysis cycle completed: durationMs={} enqueued={} processed={} completed={} retried={} failed={} paused={}",
                    report.durationMs(), report.enqueued(), report.processed(), report.completed(), report.retried(),
                    report.failed(), report.paused());
        }
        // Only changes are logged, because an exhausted quota lasts for many cycles.
        boolean wasExhausted = tracker.lastReport().map(AnalysisCycleReport::quotaExhausted).orElse(false);
        if (report.quotaExhausted() && !wasExhausted) {
            log.warn("Daily AI quota of {} invocations is exhausted; analyses wait until it becomes available",
                    settings.dailyLimit());
        } else if (!report.quotaExhausted() && wasExhausted) {
            log.info("Daily AI quota is available again");
        }
    }

    @PreDestroy
    public void stop() {
        processor.stop();
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Analyses did not stop in time; they can be claimed again later");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
