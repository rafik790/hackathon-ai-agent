package com.hackathon.agent.evidence.scheduler;

import com.hackathon.agent.evidence.service.FailureEvidenceService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers evidence cycles on a thread of its own.
 * <p>
 * Spring runs all fixed-delay jobs on one thread, so a long evidence cycle would hold up the Phase 1 collector.
 * The trigger therefore only hands the cycle over and returns. The worker thread is deliberately not a Spring
 * bean, because an executor or scheduler bean would replace the one Phase 1 uses.
 */
@Component
@ConditionalOnBooleanProperty(name = "evidence.enabled")
public class FailureEvidenceScheduler {

    private static final Logger log = LoggerFactory.getLogger(FailureEvidenceScheduler.class);

    private final FailureEvidenceService evidenceService;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("evidence-worker").factory());

    public FailureEvidenceScheduler(FailureEvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @Scheduled(fixedRateString = "${evidence.polling-interval-ms:30000}",
            initialDelayString = "${evidence.initial-delay-ms:20000}")
    public void trigger() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Evidence cycle still running; trigger ignored");
            return;
        }
        try {
            worker.execute(this::runCycle);
        } catch (RejectedExecutionException e) {
            running.set(false);
        }
    }

    private void runCycle() {
        try {
            evidenceService.runCycle();
        } catch (RuntimeException e) {
            log.error("Evidence cycle aborted unexpectedly; the next cycle will run as scheduled", e);
        } finally {
            running.set(false);
        }
    }

    @PreDestroy
    void stop() throws InterruptedException {
        worker.shutdownNow();
        if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn("Evidence cycle did not stop in time");
        }
    }
}
