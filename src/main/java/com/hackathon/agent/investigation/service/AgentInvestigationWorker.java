package com.hackathon.agent.investigation.service;

import com.hackathon.agent.investigation.AgentInvestigationProperties;
import com.hackathon.agent.investigation.service.AgentInvestigationStore.Claim;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs investigations one after the other on a thread of its own, so neither the API nor the existing schedulers wait
 * for them. Deliberately not a Spring executor bean, which would replace the one used for monitoring.
 */
@Component
@ConditionalOnBooleanProperty(name = "agent.investigation.enabled")
public class AgentInvestigationWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentInvestigationWorker.class);

    private final AgentInvestigationStore store;
    private final AgentInvestigationRunner runner;
    private final AgentInvestigationProperties properties;
    private ScheduledExecutorService executor;

    public AgentInvestigationWorker(AgentInvestigationStore store, AgentInvestigationRunner runner,
                                    AgentInvestigationProperties properties) {
        this.store = store;
        this.runner = runner;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("agent-investigation").daemon().factory());
        executor.scheduleWithFixedDelay(this::processPending, properties.pollIntervalMs(), properties.pollIntervalMs(),
                TimeUnit.MILLISECONDS);
        log.info("Agent investigation enabled: maxSteps={} maxToolCalls={} timeoutSeconds={}", properties.maxSteps(),
                properties.maxToolCalls(), properties.timeoutSeconds());
    }

    /** Processes every pending investigation; also called directly by tests. */
    public void processPending() {
        try {
            Optional<Claim> claim;
            while (!Thread.currentThread().isInterrupted() && (claim = store.claimNext()).isPresent()) {
                runner.run(claim.get());
            }
        } catch (RuntimeException e) {
            // A failed cycle must not cancel the schedule.
            log.error("Agent investigation cycle failed", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Agent investigation did not stop in time; its lease will expire");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
