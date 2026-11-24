package com.hackathon.agent.evidence.service;

import com.hackathon.agent.evidence.client.EvidenceApiException;
import com.hackathon.agent.evidence.client.KubernetesEvidenceClient;
import com.hackathon.agent.evidence.model.AttemptEvidence;
import com.hackathon.agent.evidence.model.ContainerRunState;
import com.hackathon.agent.evidence.model.ContainerSnapshot;
import com.hackathon.agent.evidence.model.EvidenceCounters;
import com.hackathon.agent.evidence.model.EvidenceStatus;
import com.hackathon.agent.evidence.model.FailedTaskRun;
import com.hackathon.agent.evidence.model.LogSnapshot;
import com.hackathon.agent.evidence.model.LogStatus;
import com.hackathon.agent.evidence.model.PodSnapshot;
import com.hackathon.agent.evidence.model.SanitizedLog;
import com.hackathon.agent.evidence.model.StoredAttempt;
import com.hackathon.agent.evidence.model.StoredLog;
import com.hackathon.agent.evidence.model.TaskRunAttempt;
import com.hackathon.agent.evidence.parser.PodEvidenceParser;
import com.hackathon.agent.evidence.parser.PrimaryFailureSelector;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import com.hackathon.agent.evidence.service.ContainerLogCollector.LogRequest;
import io.fabric8.kubernetes.api.model.Pod;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Collects the evidence of one TaskRun attempt from the cluster. Performs no database access.
 */
@Component
@ConditionalOnBooleanProperty(name = "evidence.enabled")
public class AttemptEvidenceCollector {

    private final KubernetesEvidenceClient client;
    private final PodEvidenceParser podParser;
    private final PrimaryFailureSelector selector;
    private final ContainerLogCollector logCollector;
    private final LogSanitizer sanitizer;
    private final EvidenceRetryPolicy retryPolicy;

    public AttemptEvidenceCollector(KubernetesEvidenceClient client, PodEvidenceParser podParser,
                                    PrimaryFailureSelector selector, ContainerLogCollector logCollector,
                                    LogSanitizer sanitizer, EvidenceRetryPolicy retryPolicy) {
        this.client = client;
        this.podParser = podParser;
        this.selector = selector;
        this.logCollector = logCollector;
        this.sanitizer = sanitizer;
        this.retryPolicy = retryPolicy;
    }

    /**
     * @param stored evidence persisted by an earlier, incomplete collection, or null
     */
    AttemptEvidence collect(FailedTaskRun taskRun, TaskRunAttempt attempt, StoredAttempt stored, LogBudget budget,
                            EvidenceCounters counters) {
        int fetchAttempts = stored == null ? 1 : stored.fetchAttempts() + 1;
        boolean podSeen = stored != null && stored.podSeen();
        if (attempt.podName() == null) {
            return withoutPod(attempt, EvidenceStatus.NO_POD, fetchAttempts, "TaskRun has no Pod");
        }

        Optional<Pod> pod;
        try {
            pod = client.getPod(taskRun.namespace(), attempt.podName());
        } catch (EvidenceApiException e) {
            counters.apiFailure(e.category());
            EvidenceStatus status = retryPolicy.statusAfter(e.category(), fetchAttempts);
            return withoutPod(attempt, status.isFinal() && podSeen ? EvidenceStatus.PARTIAL : status, fetchAttempts,
                    e.getMessage());
        }
        EvidenceStatus gone = podSeen ? EvidenceStatus.PARTIAL : EvidenceStatus.POD_DELETED;
        if (pod.isEmpty()) {
            return withoutPod(attempt, gone, fetchAttempts, "Pod no longer exists");
        }
        if (!podParser.isOwnedBy(pod.get(), taskRun.uid())) {
            return withoutPod(attempt, gone, fetchAttempts, "Pod is not owned by this TaskRun");
        }

        PodSnapshot snapshot = podParser.parse(pod.get(), attempt);
        PrimaryFailureSelector.Selection selection = selector.select(snapshot.containers(), attempt.reason());
        List<StoredLog> storedLogs = stored == null ? List.of() : stored.logs();
        List<LogSnapshot> logs = collectLogs(taskRun.namespace(), attempt, selection.relevant(), storedLogs, budget,
                counters);

        EvidenceStatus status = status(logs, storedLogs, selection.relevant(), fetchAttempts);
        return new AttemptEvidence(attempt.attempt(), status, attempt.podName(), snapshot, snapshot.containers(),
                selection.primary(), logs, fetchAttempts, retryPolicy.nextAttemptAt(status, fetchAttempts),
                incompleteLogs(logs));
    }

    private AttemptEvidence withoutPod(TaskRunAttempt attempt, EvidenceStatus status, int fetchAttempts, String error) {
        return new AttemptEvidence(attempt.attempt(), status, attempt.podName(), null, attempt.containers(),
                selector.select(attempt.containers(), attempt.reason()).primary(), List.of(), fetchAttempts,
                retryPolicy.nextAttemptAt(status, fetchAttempts), error);
    }

    private List<LogSnapshot> collectLogs(String namespace, TaskRunAttempt attempt, List<ContainerSnapshot> relevant,
                                          List<StoredLog> storedLogs, LogBudget budget, EvidenceCounters counters) {
        List<LogSnapshot> results = new ArrayList<>();
        List<LogRequest> requests = new ArrayList<>();
        for (ContainerSnapshot container : relevant) {
            if (!budget.admit(attempt.attempt(), container.name())) {
                counters.logsSkipped.incrementAndGet();
                continue;
            }
            List<LogRequest> wanted = new ArrayList<>();
            wanted.add(new LogRequest(container.name(), false));
            if (container.hasRestarted()) {
                wanted.add(new LogRequest(container.name(), true));
            }
            for (LogRequest request : wanted) {
                if (isSettled(request, storedLogs)) {
                    counters.logsSkipped.incrementAndGet();
                } else if (container.state() == ContainerRunState.WAITING && !container.hasRestarted()) {
                    results.add(new LogSnapshot(request.container(), false, LogStatus.NOT_STARTED, null, null));
                    counters.logsSkipped.incrementAndGet();
                } else if (budget.remainingBytes() == 0) {
                    results.add(skippedByLimit(request));
                    counters.logsSkipped.incrementAndGet();
                } else {
                    requests.add(request);
                }
            }
        }
        // Results are in order of relevance, so the most relevant logs use the budget first.
        for (LogSnapshot log : logCollector.collect(namespace, attempt.podName(), requests, counters)) {
            results.add(applyBudget(log, budget));
        }
        return results;
    }

    private LogSnapshot applyBudget(LogSnapshot log, LogBudget budget) {
        if (log.status() != LogStatus.COLLECTED) {
            return log;
        }
        SanitizedLog shortened = sanitizer.shorten(log.log(), budget.remainingBytes());
        if (shortened.content().isEmpty()) {
            return skippedByLimit(new LogRequest(log.containerName(), log.previous()));
        }
        budget.use(shortened.storedBytes());
        return new LogSnapshot(log.containerName(), log.previous(), log.status(), shortened, log.fetchedAt());
    }

    private static LogSnapshot skippedByLimit(LogRequest request) {
        return new LogSnapshot(request.container(), request.previous(), LogStatus.SKIPPED_LIMIT, null, null);
    }

    private static boolean isSettled(LogRequest request, List<StoredLog> storedLogs) {
        return storedLogs.stream().anyMatch(stored -> stored.containerName().equals(request.container())
                && stored.previous() == request.previous() && !stored.status().isRetryable());
    }

    private EvidenceStatus status(List<LogSnapshot> logs, List<StoredLog> storedLogs,
                                  List<ContainerSnapshot> relevant, int fetchAttempts) {
        if (logs.stream().anyMatch(log -> log.status().isRetryable()) && !retryPolicy.isExhausted(fetchAttempts)) {
            return EvidenceStatus.PENDING;
        }
        boolean newComplete = logs.stream().allMatch(log -> log.status().isComplete());
        boolean storedComplete = storedLogs.stream()
                .filter(stored -> !stored.status().isRetryable())
                .filter(stored -> relevant.stream().anyMatch(c -> c.name().equals(stored.containerName())))
                .allMatch(stored -> stored.status().isComplete());
        return newComplete && storedComplete ? EvidenceStatus.COLLECTED : EvidenceStatus.PARTIAL;
    }

    private static String incompleteLogs(List<LogSnapshot> logs) {
        List<String> incomplete = logs.stream()
                .filter(log -> !log.status().isComplete())
                .map(log -> log.containerName() + (log.previous() ? " (previous)=" : "=") + log.status())
                .toList();
        return incomplete.isEmpty() ? null : "Incomplete logs: " + String.join(", ", incomplete);
    }
}
