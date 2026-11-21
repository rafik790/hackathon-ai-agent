package com.hackathon.agent.scheduler;

import com.hackathon.agent.collector.TektonCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers collection cycles. {@code fixedDelay} starts the next cycle only after the previous one
 * finished, so cycles never overlap even when the Kubernetes API is slow.
 */
@Component
@ConditionalOnBooleanProperty(name = "monitoring.enabled", matchIfMissing = true)
public class TektonCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(TektonCollectionScheduler.class);

    private final TektonCollectionService collectionService;

    public TektonCollectionScheduler(TektonCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @Scheduled(fixedDelayString = "${monitoring.polling-interval-ms}",
            initialDelayString = "${monitoring.initial-delay-ms:5000}")
    public void collect() {
        try {
            collectionService.runCycle();
        } catch (RuntimeException e) {
            // Never let an exception escape: it would be logged by the scheduler but is reported here with context.
            log.error("Collection cycle aborted unexpectedly; next cycle will run as scheduled", e);
        }
    }
}
