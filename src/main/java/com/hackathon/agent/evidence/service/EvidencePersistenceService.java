package com.hackathon.agent.evidence.service;

import com.hackathon.agent.config.EvidenceProperties;
import com.hackathon.agent.evidence.client.EvidenceApiException;
import com.hackathon.agent.evidence.entity.ContainerEvidenceEntity;
import com.hackathon.agent.evidence.entity.ContainerLogEntity;
import com.hackathon.agent.evidence.entity.FailureEvidenceEntity;
import com.hackathon.agent.evidence.model.AttemptEvidence;
import com.hackathon.agent.evidence.model.ContainerSnapshot;
import com.hackathon.agent.evidence.model.EvidenceStatus;
import com.hackathon.agent.evidence.model.FailedTaskRun;
import com.hackathon.agent.evidence.model.LogSnapshot;
import com.hackathon.agent.evidence.model.LogStatus;
import com.hackathon.agent.evidence.model.PodSnapshot;
import com.hackathon.agent.evidence.model.SanitizedLog;
import com.hackathon.agent.evidence.model.StoredAttempt;
import com.hackathon.agent.evidence.repository.ContainerEvidenceRepository;
import com.hackathon.agent.evidence.repository.ContainerLogRepository;
import com.hackathon.agent.evidence.repository.FailedTaskRunRepository;
import com.hackathon.agent.evidence.repository.FailureEvidenceRepository;
import com.hackathon.agent.evidence.sanitizer.LogSanitizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database side of evidence collection: short transactions only, no Kubernetes access.
 * <p>
 * All attempts of a TaskRun are written in one transaction, and rows are matched by the unique keys of the
 * schema. Final evidence and settled logs are never overwritten.
 */
@Service
public class EvidencePersistenceService {

    private static final int MAX_MESSAGE = 4000;
    private static final int MAX_REASON = 255;

    /** Number of failure_evidence rows written. */
    public record SaveResult(int created, int updated) {
    }

    private final FailedTaskRunRepository failedTaskRuns;
    private final FailureEvidenceRepository evidenceRepository;
    private final ContainerEvidenceRepository containerRepository;
    private final ContainerLogRepository logRepository;
    private final LogSanitizer sanitizer;
    private final EvidenceRetryPolicy retryPolicy;
    private final EvidenceProperties properties;
    private final Clock clock;

    public EvidencePersistenceService(FailedTaskRunRepository failedTaskRuns,
                                      FailureEvidenceRepository evidenceRepository,
                                      ContainerEvidenceRepository containerRepository,
                                      ContainerLogRepository logRepository, LogSanitizer sanitizer,
                                      EvidenceRetryPolicy retryPolicy, EvidenceProperties properties, Clock clock) {
        this.failedTaskRuns = failedTaskRuns;
        this.evidenceRepository = evidenceRepository;
        this.containerRepository = containerRepository;
        this.logRepository = logRepository;
        this.sanitizer = sanitizer;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.clock = clock;
    }

    /** Due retries (at most half of the batch, so they cannot starve new failures) followed by new failures. */
    @Transactional(readOnly = true)
    public List<FailedTaskRun> findCandidates(Collection<String> namespaces) {
        Instant now = clock.instant();
        int batch = properties.batchSize();
        List<FailedTaskRun> candidates = new ArrayList<>(
                failedTaskRuns.findDueForRetry(now, namespaces, Limit.of(Math.max(1, batch / 2))));
        if (candidates.size() < batch) {
            candidates.addAll(failedTaskRuns.findWithoutEvidence(
                    now.minus(Duration.ofHours(properties.lookbackHours())), namespaces,
                    Limit.of(batch - candidates.size())));
        }
        return candidates;
    }

    /** What is already stored for a TaskRun, by attempt. */
    @Transactional(readOnly = true)
    public Map<Integer, StoredAttempt> loadStored(Long taskRunId) {
        Map<Integer, StoredAttempt> stored = new HashMap<>();
        for (FailureEvidenceEntity e : evidenceRepository.findByTaskRunIdOrderByAttempt(taskRunId)) {
            stored.put(e.getAttempt(), new StoredAttempt(e.getAttempt(), e.getStatus(), e.getFetchAttempts(),
                    e.getPodUid() != null, logRepository.findStoredLogs(e.getId())));
        }
        return stored;
    }

    @Transactional
    public SaveResult save(Long taskRunId, List<AttemptEvidence> attempts) {
        Map<Integer, FailureEvidenceEntity> existing = existing(taskRunId);
        int created = 0;
        int updated = 0;
        for (AttemptEvidence attempt : attempts) {
            FailureEvidenceEntity entity = existing.get(attempt.attempt());
            if (entity != null && entity.getStatus().isFinal()) {
                continue;
            }
            if (entity == null) {
                entity = newEvidence(taskRunId, attempt.attempt());
                created++;
            } else {
                updated++;
            }
            apply(entity, attempt);
            Long evidenceId = evidenceRepository.save(entity).getId();
            Map<String, ContainerEvidenceEntity> containers = saveContainers(evidenceId, attempt);
            saveLogs(containers, attempt.logs());
        }
        return new SaveResult(created, updated);
    }

    /** Records a collection that failed before the attempts of the TaskRun were known. */
    @Transactional
    public SaveResult recordFailure(Long taskRunId, EvidenceApiException.Category category, String error) {
        return updatePending(taskRunId, error, entity -> {
            EvidenceStatus status = retryPolicy.statusAfter(category, entity.getFetchAttempts());
            return status.isFinal() && entity.getPodUid() != null ? EvidenceStatus.PARTIAL : status;
        });
    }

    /** Records that the TaskRun itself no longer exists. */
    @Transactional
    public SaveResult recordDeleted(Long taskRunId, String error) {
        return updatePending(taskRunId, error, entity ->
                entity.getPodUid() != null ? EvidenceStatus.PARTIAL : EvidenceStatus.RESOURCE_DELETED);
    }

    private SaveResult updatePending(Long taskRunId, String error,
                                     Function<FailureEvidenceEntity, EvidenceStatus> statusOf) {
        Map<Integer, FailureEvidenceEntity> existing = existing(taskRunId);
        // The attempt number is unknown before the TaskRun was read; attempt 0 always exists.
        Collection<FailureEvidenceEntity> entities = existing.isEmpty()
                ? List.of(newEvidence(taskRunId, 0)) : existing.values();
        int updated = 0;
        for (FailureEvidenceEntity entity : entities) {
            if (entity.getStatus() != null && entity.getStatus().isFinal()) {
                continue;
            }
            entity.setFetchAttempts(entity.getFetchAttempts() + 1);
            applyOutcome(entity, statusOf.apply(entity), error);
            evidenceRepository.save(entity);
            updated++;
        }
        return existing.isEmpty() ? new SaveResult(updated, 0) : new SaveResult(0, updated);
    }

    private Map<Integer, FailureEvidenceEntity> existing(Long taskRunId) {
        Map<Integer, FailureEvidenceEntity> existing = new HashMap<>();
        evidenceRepository.findByTaskRunIdOrderByAttempt(taskRunId).forEach(e -> existing.put(e.getAttempt(), e));
        return existing;
    }

    private FailureEvidenceEntity newEvidence(Long taskRunId, int attempt) {
        FailureEvidenceEntity entity = new FailureEvidenceEntity();
        entity.setTaskRunId(taskRunId);
        entity.setAttempt(attempt);
        entity.setCreatedAt(clock.instant());
        return entity;
    }

    private void apply(FailureEvidenceEntity entity, AttemptEvidence attempt) {
        entity.setFetchAttempts(attempt.fetchAttempts());
        if (attempt.podName() != null) {
            entity.setPodName(attempt.podName());
        }
        PodSnapshot pod = attempt.pod();
        if (pod != null) {
            entity.setPodUid(pod.uid());
            entity.setPodPhase(limit(pod.phase(), 16));
            entity.setPodReason(limit(pod.reason(), MAX_REASON));
            entity.setPodMessage(sanitizer.sanitizeMessage(pod.message(), MAX_MESSAGE));
        }
        applyOutcome(entity, attempt.status(), attempt.error());
    }

    private void applyOutcome(FailureEvidenceEntity entity, EvidenceStatus status, String error) {
        Instant now = clock.instant();
        entity.setStatus(status);
        entity.setNextAttemptAt(retryPolicy.nextAttemptAt(status, entity.getFetchAttempts()));
        entity.setLastError(sanitizer.sanitizeMessage(error, MAX_MESSAGE));
        entity.setCollectedAt(status.isFinal() ? now : null);
        entity.setUpdatedAt(now);
    }

    private Map<String, ContainerEvidenceEntity> saveContainers(Long evidenceId, AttemptEvidence attempt) {
        Map<String, ContainerEvidenceEntity> entities = new HashMap<>();
        containerRepository.findByEvidenceId(evidenceId).forEach(e -> entities.put(e.getContainerName(), e));
        if (attempt.pod() == null && !entities.isEmpty()) {
            // What was read from the Pod earlier is better than the TaskRun's summary of it.
            return entities;
        }
        Instant now = clock.instant();
        for (ContainerSnapshot snapshot : attempt.containers()) {
            ContainerEvidenceEntity entity = entities.computeIfAbsent(snapshot.name(), name -> {
                ContainerEvidenceEntity created = new ContainerEvidenceEntity();
                created.setEvidenceId(evidenceId);
                created.setContainerName(name);
                created.setCreatedAt(now);
                return created;
            });
            entity.setContainerType(snapshot.type());
            entity.setStepName(limit(snapshot.stepName(), 253));
            entity.setOrdinal(snapshot.ordinal());
            entity.setState(snapshot.state());
            entity.setExitCode(snapshot.exitCode());
            entity.setExitCodeSource(snapshot.exitCodeSource());
            entity.setSignal(snapshot.signal());
            entity.setReason(limit(snapshot.reason(), MAX_REASON));
            entity.setTektonTerminationReason(limit(snapshot.tektonTerminationReason(), MAX_REASON));
            entity.setMessage(sanitizer.sanitizeMessage(snapshot.message(), MAX_MESSAGE));
            entity.setRestartCount(snapshot.restartCount());
            entity.setStartedAt(snapshot.startedAt());
            entity.setFinishedAt(snapshot.finishedAt());
            entity.setPrimaryFailure(snapshot.name().equals(attempt.primaryContainer()));
            entity.setUpdatedAt(now);
            containerRepository.save(entity);
        }
        return entities;
    }

    private void saveLogs(Map<String, ContainerEvidenceEntity> containers, List<LogSnapshot> logs) {
        for (LogSnapshot log : logs) {
            Long containerId = containers.get(log.containerName()).getId();
            ContainerLogEntity entity = logRepository.findByContainerEvidenceId(containerId).stream()
                    .filter(e -> e.isPrevious() == log.previous())
                    .findFirst()
                    .orElse(null);
            if (entity != null && !entity.getStatus().isRetryable()) {
                continue;
            }
            Instant now = clock.instant();
            if (entity == null) {
                entity = new ContainerLogEntity();
                entity.setContainerEvidenceId(containerId);
                entity.setPrevious(log.previous());
                entity.setCreatedAt(now);
            }
            apply(entity, log, now);
            logRepository.save(entity);
        }
    }

    private void apply(ContainerLogEntity entity, LogSnapshot log, Instant now) {
        entity.setStatus(log.status());
        entity.setFetchedAt(log.fetchedAt());
        entity.setExpiresAt((log.fetchedAt() == null ? now : log.fetchedAt())
                .plus(Duration.ofDays(properties.retentionDays())));
        entity.setUpdatedAt(now);
        if (log.status() == LogStatus.COLLECTED) {
            SanitizedLog text = log.log();
            entity.setContent(text.content());
            entity.setStoredBytes(text.storedBytes());
            entity.setTruncated(text.truncated());
            entity.setSha256(text.sha256());
            entity.setRedactions(text.redactions());
        }
    }

    private static String limit(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
