package com.hackathon.agent.service;

import com.hackathon.agent.collector.CollectionStatusTracker;
import com.hackathon.agent.config.MonitoringProperties;
import com.hackathon.agent.dto.CollectorStatusResponse;
import org.springframework.stereotype.Service;

@Service
public class CollectorStatusService {

    private final CollectionStatusTracker tracker;
    private final MonitoringProperties properties;

    public CollectorStatusService(CollectionStatusTracker tracker, MonitoringProperties properties) {
        this.tracker = tracker;
        this.properties = properties;
    }

    public CollectorStatusResponse status() {
        CollectorStatusResponse.LastCycle lastCycle = tracker.lastReport()
                .map(r -> new CollectorStatusResponse.LastCycle(r.startedAt(), r.finishedAt(), r.durationMs(),
                        r.successful(), r.discoveredByKind(), r.created(), r.updated(), r.unchanged(), r.skipped(),
                        r.errors()))
                .orElse(null);
        return new CollectorStatusResponse(properties.enabled(), properties.namespaces(),
                properties.pollingIntervalMs(), tracker.totalCycles(), tracker.consecutiveFailedCycles(),
                tracker.lastSuccessfulCycleAt().orElse(null), lastCycle);
    }
}
