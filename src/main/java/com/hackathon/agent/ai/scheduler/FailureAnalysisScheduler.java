package com.hackathon.agent.ai.scheduler;

import com.hackathon.agent.ai.service.FailureAnalysisService;
import com.hackathon.agent.config.AiProperties;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs analysis cycles on a thread of its own, completely independent of the other schedulers.
 * <p>
 * Spring's scheduling is deliberately not used: it is only active while monitoring is enabled, and activating it
 * here would also start the evidence worker. A fixed delay guarantees that cycles never overlap.
 */
@Component
@ConditionalOnBooleanProperty(name = "ai.enabled")
public class FailureAnalysisScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FailureAnalysisScheduler.class);

    private final FailureAnalysisService analysisService;
    private final AiProperties properties;
    private ScheduledThreadPoolExecutor executor;

    public FailureAnalysisScheduler(FailureAnalysisService analysisService, AiProperties properties) {
        this.analysisService = analysisService;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("ai-scheduler").daemon().factory());
        executor.scheduleWithFixedDelay(this::runCycle, properties.analysis().initialDelayMs(),
                properties.analysis().pollingIntervalMs(), TimeUnit.MILLISECONDS);
        log.info("AI failure analysis enabled: provider={}", properties.provider());
    }

    /** An exception would cancel all further cycles. */
    private void runCycle() {
        try {
            analysisService.runCycle();
        } catch (RuntimeException e) {
            log.error("Analysis cycle aborted unexpectedly; the next cycle will run as scheduled", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            analysisService.stop();
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
