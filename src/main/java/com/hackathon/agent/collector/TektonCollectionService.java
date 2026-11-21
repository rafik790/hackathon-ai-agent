package com.hackathon.agent.collector;

import com.hackathon.agent.config.MonitoringProperties;
import com.hackathon.agent.kubernetes.KubernetesAccessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Runs a collection cycle: every collector for every configured namespace.
 * <p>
 * Failures are isolated per (collector, namespace) pair, logged and reported, so one unreachable
 * namespace or a transient database error never aborts the cycle or the application. Overlapping
 * cycles are prevented with a non-blocking lock.
 */
@Service
public class TektonCollectionService {

    private static final Logger log = LoggerFactory.getLogger(TektonCollectionService.class);

    private final List<ResourceCollector> collectors;
    private final MonitoringProperties properties;
    private final CollectionStatusTracker statusTracker;
    private final Clock clock;
    private final ReentrantLock cycleLock = new ReentrantLock();

    public TektonCollectionService(List<ResourceCollector> collectors, MonitoringProperties properties,
                                   CollectionStatusTracker statusTracker, Clock clock) {
        this.collectors = collectors;
        this.properties = properties;
        this.statusTracker = statusTracker;
        this.clock = clock;
    }

    /**
     * @return the cycle report, or empty when a cycle was already running
     */
    public Optional<CollectionCycleReport> runCycle() {
        if (!cycleLock.tryLock()) {
            log.warn("Collection cycle skipped: previous cycle still running");
            return Optional.empty();
        }
        try {
            CollectionCycleReport report = executeCycle();
            statusTracker.record(report);
            return Optional.of(report);
        } finally {
            cycleLock.unlock();
        }
    }

    private CollectionCycleReport executeCycle() {
        Instant startedAt = Instant.now(clock);
        log.info("Collection cycle started: namespaces={}, collectors={}", properties.namespaces(),
                collectors.stream().map(ResourceCollector::resourceKind).toList());

        Map<String, Integer> discovered = new LinkedHashMap<>();
        collectors.forEach(c -> discovered.put(c.resourceKind(), 0));
        List<String> errors = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int skipped = 0;

        for (String namespace : properties.namespaces()) {
            for (ResourceCollector collector : collectors) {
                try {
                    CollectionResult result = collector.collect(namespace);
                    discovered.merge(result.resourceKind(), result.discovered(), Integer::sum);
                    created += result.upsert().created();
                    updated += result.upsert().updated();
                    unchanged += result.upsert().unchanged();
                    skipped += result.skipped();
                    log.debug("Collected kind={} namespace={} discovered={} created={} updated={} unchanged={} skipped={}",
                            result.resourceKind(), namespace, result.discovered(), result.upsert().created(),
                            result.upsert().updated(), result.upsert().unchanged(), result.skipped());
                } catch (KubernetesAccessException e) {
                    errors.add(collector.resourceKind() + "/" + namespace + ": " + e.getMessage());
                    log.error("Kubernetes API error: kind={} namespace={} error={}",
                            collector.resourceKind(), namespace, e.getMessage());
                } catch (DataAccessException e) {
                    errors.add(collector.resourceKind() + "/" + namespace + ": database error: "
                            + e.getMostSpecificCause().getMessage());
                    log.error("Database error: kind={} namespace={}", collector.resourceKind(), namespace, e);
                } catch (RuntimeException e) {
                    errors.add(collector.resourceKind() + "/" + namespace + ": " + e);
                    log.error("Unexpected collection error: kind={} namespace={}", collector.resourceKind(), namespace, e);
                }
            }
        }

        Instant finishedAt = Instant.now(clock);
        CollectionCycleReport report = new CollectionCycleReport(startedAt, finishedAt,
                Duration.between(startedAt, finishedAt).toMillis(), Collections.unmodifiableMap(discovered),
                created, updated, unchanged, skipped, List.copyOf(errors));
        if (report.successful()) {
            log.info("Collection cycle completed: durationMs={} discovered={} created={} updated={} unchanged={} skipped={}",
                    report.durationMs(), discovered, created, updated, unchanged, skipped);
        } else {
            log.warn("Collection cycle completed with errors: durationMs={} discovered={} created={} updated={} errors={}",
                    report.durationMs(), discovered, created, updated, errors.size());
        }
        return report;
    }
}
