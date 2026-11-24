package com.hackathon.agent.evidence.service;

import com.hackathon.agent.config.EvidenceProperties;
import com.hackathon.agent.config.MonitoringProperties;
import com.hackathon.agent.evidence.client.EvidenceApiException;
import com.hackathon.agent.evidence.client.KubernetesEvidenceClient;
import com.hackathon.agent.evidence.model.AttemptEvidence;
import com.hackathon.agent.evidence.model.EvidenceCounters;
import com.hackathon.agent.evidence.model.EvidenceCycleReport;
import com.hackathon.agent.evidence.model.FailedTaskRun;
import com.hackathon.agent.evidence.model.StoredAttempt;
import com.hackathon.agent.evidence.model.TaskRunAttempt;
import com.hackathon.agent.evidence.model.TaskRunDetails;
import com.hackathon.agent.evidence.parser.TaskRunEvidenceParser;
import com.hackathon.agent.evidence.service.EvidencePersistenceService.SaveResult;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

/**
 * Runs evidence collection cycles. For every TaskRun all Kubernetes requests happen first, outside any
 * transaction; the result is then persisted in one short transaction. Errors are isolated per TaskRun.
 */
@Service
@ConditionalOnBooleanProperty(name = "evidence.enabled")
public class FailureEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(FailureEvidenceService.class);

    private static final SaveResult NOTHING_SAVED = new SaveResult(0, 0);

    private final EvidencePersistenceService persistence;
    private final KubernetesEvidenceClient client;
    private final TaskRunEvidenceParser taskRunParser;
    private final AttemptEvidenceCollector attemptCollector;
    private final EvidenceStatusTracker tracker;
    private final EvidenceProperties properties;
    private final MonitoringProperties monitoring;
    private final Clock clock;
    private final ReentrantLock cycleLock = new ReentrantLock();

    public FailureEvidenceService(EvidencePersistenceService persistence, KubernetesEvidenceClient client,
                                  TaskRunEvidenceParser taskRunParser, AttemptEvidenceCollector attemptCollector,
                                  EvidenceStatusTracker tracker, EvidenceProperties properties,
                                  MonitoringProperties monitoring, Clock clock) {
        this.persistence = persistence;
        this.client = client;
        this.taskRunParser = taskRunParser;
        this.attemptCollector = attemptCollector;
        this.tracker = tracker;
        this.properties = properties;
        this.monitoring = monitoring;
        this.clock = clock;
    }

    /** @return the cycle report, or empty when a cycle was already running */
    public Optional<EvidenceCycleReport> runCycle() {
        if (!cycleLock.tryLock()) {
            log.warn("Evidence cycle skipped: previous cycle still running");
            return Optional.empty();
        }
        try {
            EvidenceCycleReport report = executeCycle();
            tracker.record(report);
            return Optional.of(report);
        } finally {
            cycleLock.unlock();
        }
    }

    private EvidenceCycleReport executeCycle() {
        Instant startedAt = clock.instant();
        EvidenceCounters counters = new EvidenceCounters();
        try {
            for (FailedTaskRun taskRun : persistence.findCandidates(monitoring.namespaces())) {
                if (isStopping()) {
                    break;
                }
                counters.taskRunsExamined.incrementAndGet();
                process(taskRun, counters);
            }
        } catch (RuntimeException e) {
            counters.taskRunErrors.incrementAndGet();
            log.error("Selecting TaskRuns for evidence collection failed", e);
        }
        Instant finishedAt = clock.instant();
        EvidenceCycleReport report = new EvidenceCycleReport(startedAt, finishedAt,
                Duration.between(startedAt, finishedAt).toMillis(), counters.taskRunsExamined.get(),
                counters.evidenceCreated.get(), counters.evidenceUpdated.get(), counters.logsFetched.get(),
                counters.logsSkipped.get(), counters.failedApiCalls.get(), counters.forbiddenApiCalls.get(),
                counters.taskRunErrors.get());
        if (report.taskRunsExamined() > 0 || !report.successful()) {
            log.info("Evidence cycle completed: durationMs={} taskRunsExamined={} evidenceCreated={} evidenceUpdated={} "
                            + "logsFetched={} logsSkipped={} failedApiCalls={} forbiddenApiCalls={} taskRunErrors={}",
                    report.durationMs(), report.taskRunsExamined(), report.evidenceCreated(), report.evidenceUpdated(),
                    report.logsFetched(), report.logsSkipped(), report.failedApiCalls(), report.forbiddenApiCalls(),
                    report.taskRunErrors());
        }
        return report;
    }

    private void process(FailedTaskRun taskRun, EvidenceCounters counters) {
        try {
            count(collect(taskRun, counters), counters);
        } catch (RuntimeException e) {
            if (isStopping()) {
                return;
            }
            counters.taskRunErrors.incrementAndGet();
            log.error("Evidence collection failed: taskRun={}/{}", taskRun.namespace(), taskRun.name(), e);
            recordUnexpectedFailure(taskRun, e, counters);
        }
    }

    private SaveResult collect(FailedTaskRun taskRun, EvidenceCounters counters) {
        Map<Integer, StoredAttempt> stored = persistence.loadStored(taskRun.id());
        Optional<GenericKubernetesResource> resource;
        try {
            resource = client.getTaskRun(taskRun.namespace(), taskRun.name());
        } catch (EvidenceApiException e) {
            if (isStopping()) {
                return NOTHING_SAVED;
            }
            counters.apiFailure(e.category());
            log.warn("{}", e.getMessage());
            return persistence.recordFailure(taskRun.id(), e.category(), e.getMessage());
        }
        if (resource.isEmpty()) {
            return persistence.recordDeleted(taskRun.id(), "TaskRun no longer exists");
        }
        TaskRunDetails details = taskRunParser.parse(resource.get());
        if (!taskRun.uid().equals(details.uid())) {
            return persistence.recordDeleted(taskRun.id(), "TaskRun name now belongs to another TaskRun");
        }
        if (!details.failed()) {
            return persistence.recordFailure(taskRun.id(), EvidenceApiException.Category.TRANSIENT,
                    "Kubernetes does not report the TaskRun as failed");
        }

        LogBudget budget = new LogBudget(properties.maxTaskrunLogBytes(), stored.values());
        List<AttemptEvidence> collected = new ArrayList<>();
        // The last attempt decided the outcome, so it gets the log budget first.
        for (TaskRunAttempt attempt : details.attempts().reversed()) {
            StoredAttempt known = stored.get(attempt.attempt());
            if (known == null || !known.status().isFinal()) {
                collected.add(attemptCollector.collect(taskRun, attempt, known, budget, counters));
            }
        }
        return isStopping() ? NOTHING_SAVED : persistence.save(taskRun.id(), collected);
    }

    /** Requests interrupted by a shutdown say nothing about the cluster and must not use up an attempt. */
    private static boolean isStopping() {
        return Thread.currentThread().isInterrupted();
    }

    /** Counts the failed attempt, so a TaskRun that always fails is not processed forever. */
    private void recordUnexpectedFailure(FailedTaskRun taskRun, RuntimeException cause, EvidenceCounters counters) {
        try {
            count(persistence.recordFailure(taskRun.id(), EvidenceApiException.Category.TRANSIENT,
                    "Unexpected error: " + cause.getClass().getSimpleName()), counters);
        } catch (RuntimeException e) {
            log.error("Recording the failed evidence collection failed: taskRun={}/{}", taskRun.namespace(),
                    taskRun.name(), e);
        }
    }

    private static void count(SaveResult result, EvidenceCounters counters) {
        counters.evidenceCreated.addAndGet(result.created());
        counters.evidenceUpdated.addAndGet(result.updated());
    }
}
