package com.hackathon.agent.resolution.service;

import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.exception.ConflictException;
import com.hackathon.agent.exception.InvalidRequestException;
import com.hackathon.agent.exception.ResourceNotFoundException;
import com.hackathon.agent.investigation.model.AgentReport.Team;
import com.hackathon.agent.repository.PipelineRunRepository;
import com.hackathon.agent.repository.TaskRunRepository;
import com.hackathon.agent.resolution.api.ResolutionDtos.PipelineTaskRunSummary;
import com.hackathon.agent.resolution.api.ResolutionDtos.ResolutionHistoryEntry;
import com.hackathon.agent.resolution.api.ResolutionDtos.ResolutionResponse;
import com.hackathon.agent.resolution.api.ResolutionDtos.ResolutionSummary;
import com.hackathon.agent.resolution.api.ResolutionDtos.ResolutionUpdateRequest;
import com.hackathon.agent.resolution.api.ResolutionDtos.TaskRunResolutionItem;
import com.hackathon.agent.resolution.entity.ResolutionHistoryEntity;
import com.hackathon.agent.resolution.entity.ResolutionRecordEntity;
import com.hackathon.agent.resolution.model.ResolutionAggregation;
import com.hackathon.agent.resolution.model.ResolutionStatus;
import com.hackathon.agent.resolution.model.ResolutionTargetType;
import com.hackathon.agent.resolution.repository.ResolutionHistoryRepository;
import com.hackathon.agent.resolution.repository.ResolutionRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolution of failed TaskRuns, the source of truth, and of PipelineRuns, which is derived from them.
 *
 * <p>A TaskRun resolution is read and changed here with validated transitions, optimistic locking and an append-only
 * history. It needs no AI analysis or investigation, and nothing changes it automatically: every change is a request of
 * a person. A PipelineRun resolution is never stored: each read aggregates the resolutions of its failed TaskRuns with
 * {@link ResolutionAggregation}, so it follows every TaskRun change at once and cannot disagree with them.
 */
@Service
public class ResolutionService {

    static final int MAX_SUMMARY = 2000;
    static final int MAX_NOTES = 4000;
    static final int MAX_NAME = 128;
    static final int MAX_BATCH = 100;

    private final ResolutionRecordRepository records;
    private final ResolutionHistoryRepository history;
    private final TaskRunRepository taskRuns;
    private final PipelineRunRepository pipelineRuns;
    private final Clock clock;

    public ResolutionService(ResolutionRecordRepository records, ResolutionHistoryRepository history, TaskRunRepository taskRuns,
                             PipelineRunRepository pipelineRuns, Clock clock) {
        this.records = records;
        this.history = history;
        this.taskRuns = taskRuns;
        this.pipelineRuns = pipelineRuns;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- TaskRuns

    @Transactional(readOnly = true)
    public ResolutionResponse taskRun(long taskRunId) {
        TaskRunEntity taskRun = loadTaskRun(taskRunId);
        return taskRunResponse(taskRun, find(taskRunId).orElse(null));
    }

    @Transactional
    public ResolutionResponse updateTaskRun(long taskRunId, ResolutionUpdateRequest request) {
        TaskRunEntity taskRun = loadTaskRun(taskRunId);
        ResolutionRecordEntity record = find(taskRunId).orElse(null);
        Change change = validate(request, record, record != null || taskRun.getStatus() == ExecutionStatus.FAILED);
        if (record == null) {
            record = new ResolutionRecordEntity();
            record.setTargetType(ResolutionTargetType.TASK_RUN);
            record.setTargetKey(ResolutionTargetType.TASK_RUN.key(taskRunId));
            record.setTaskRunId(taskRunId);
            record.setPipelineRunId(owningPipelineRun(taskRun).map(PipelineRunEntity::getId).orElse(null));
            record.setTargetName(taskRun.getTaskRunName());
            record.setNamespace(taskRun.getNamespace());
            record.setStatus(ResolutionStatus.OPEN);
            record.setCreatedAt(clock.instant());
        }
        return taskRunResponse(taskRun, apply(record, change));
    }

    @Transactional(readOnly = true)
    public List<ResolutionHistoryEntry> taskRunHistory(long taskRunId) {
        loadTaskRun(taskRunId);
        return find(taskRunId).map(record -> historyOf(List.of(record))).orElse(List.of());
    }

    @Transactional(readOnly = true)
    public List<ResolutionSummary> taskRunSummaries(Collection<Long> ids) {
        Map<Long, TaskRunEntity> runs = taskRuns.findAllById(checkBatch(ids)).stream()
                .collect(Collectors.toMap(TaskRunEntity::getId, Function.identity()));
        Map<String, ResolutionRecordEntity> byKey = records.findByTargetKeyIn(ids.stream().map(ResolutionTargetType.TASK_RUN::key).toList())
                .stream().collect(Collectors.toMap(ResolutionRecordEntity::getTargetKey, Function.identity()));
        List<ResolutionSummary> summaries = new ArrayList<>();
        for (Long id : sorted(ids)) {
            ResolutionRecordEntity record = byKey.get(ResolutionTargetType.TASK_RUN.key(id));
            TaskRunEntity run = runs.get(id);
            if (record != null) {
                summaries.add(new ResolutionSummary(id, true, record.getStatus(), record.getAssignedTeam(), record.getUpdatedAt(), null, null));
            } else if (run != null) {
                summaries.add(new ResolutionSummary(id, false, run.getStatus() == ExecutionStatus.FAILED ? ResolutionStatus.OPEN : null,
                        null, null, null, null));
            }
        }
        return summaries;
    }

    // ---------------------------------------------------------------- PipelineRuns (derived, read-only)

    @Transactional(readOnly = true)
    public ResolutionResponse pipelineRun(long pipelineRunId) {
        PipelineRunEntity pipelineRun = loadPipelineRun(pipelineRunId);
        return pipelineRunResponse(pipelineRun, failedTaskRuns(pipelineRun));
    }

    /** All changes of the resolutions of the PipelineRun's failed TaskRuns, oldest first. */
    @Transactional(readOnly = true)
    public List<ResolutionHistoryEntry> pipelineRunHistory(long pipelineRunId) {
        PipelineRunEntity pipelineRun = loadPipelineRun(pipelineRunId);
        return historyOf(failedTaskRuns(pipelineRun).stream().map(FailedTaskRun::record).filter(Objects::nonNull).toList());
    }

    @Transactional(readOnly = true)
    public List<ResolutionSummary> pipelineRunSummaries(Collection<Long> ids) {
        Map<Long, PipelineRunEntity> runs = pipelineRuns.findAllById(checkBatch(ids)).stream()
                .collect(Collectors.toMap(PipelineRunEntity::getId, Function.identity()));
        List<ResolutionSummary> summaries = new ArrayList<>();
        for (Long id : sorted(ids)) {
            PipelineRunEntity run = runs.get(id);
            if (run != null) {
                ResolutionResponse response = pipelineRunResponse(run, failedTaskRuns(run));
                summaries.add(new ResolutionSummary(id, response.tracked(), response.status(), response.assignedTeam(), response.updatedAt(),
                        response.taskRuns().failedTaskRuns(), response.taskRuns().resolvedTaskRuns()));
            }
        }
        return summaries;
    }

    /** A failed TaskRun of a PipelineRun with its record, if any; {@code taskRun} is null when the row is gone. */
    private record FailedTaskRun(TaskRunEntity taskRun, ResolutionRecordEntity record) {

        ResolutionStatus status() {
            return record == null ? ResolutionStatus.OPEN : record.getStatus();
        }
    }

    /**
     * The failed TaskRuns of a PipelineRun: those stored now, including ones collected after earlier reads, plus
     * TaskRuns whose rows are gone but whose records name the PipelineRun.
     */
    private List<FailedTaskRun> failedTaskRuns(PipelineRunEntity pipelineRun) {
        List<TaskRunEntity> failed = taskRuns.findByPipelineRun(pipelineRun.getPipelineRunUid(), pipelineRun.getNamespace(),
                pipelineRun.getPipelineRunName()).stream().filter(t -> t.getStatus() == ExecutionStatus.FAILED).toList();
        Map<String, ResolutionRecordEntity> byKey = new LinkedHashMap<>();
        records.findByTargetKeyIn(failed.stream().map(t -> ResolutionTargetType.TASK_RUN.key(t.getId())).toList())
                .forEach(r -> byKey.put(r.getTargetKey(), r));
        records.findByPipelineRunIdAndTargetType(pipelineRun.getId(), ResolutionTargetType.TASK_RUN)
                .forEach(r -> byKey.putIfAbsent(r.getTargetKey(), r));

        List<FailedTaskRun> result = new ArrayList<>();
        for (TaskRunEntity taskRun : failed) {
            result.add(new FailedTaskRun(taskRun, byKey.remove(ResolutionTargetType.TASK_RUN.key(taskRun.getId()))));
        }
        for (ResolutionRecordEntity record : byKey.values()) {
            result.add(new FailedTaskRun(Optional.ofNullable(record.getTaskRunId()).flatMap(taskRuns::findById).orElse(null), record));
        }
        return result;
    }

    private ResolutionResponse pipelineRunResponse(PipelineRunEntity pipelineRun, List<FailedTaskRun> failed) {
        List<TaskRunResolutionItem> items = failed.stream().map(ResolutionService::item).toList();
        Map<ResolutionStatus, Integer> byStatus = new EnumMap<>(ResolutionStatus.class);
        failed.forEach(f -> byStatus.merge(f.status(), 1, Integer::sum));
        int resolved = byStatus.getOrDefault(ResolutionStatus.RESOLVED, 0);
        ResolutionStatus status = ResolutionAggregation.aggregate(failed.stream().map(FailedTaskRun::status).toList());

        List<ResolutionRecordEntity> tracked = failed.stream().map(FailedTaskRun::record).filter(Objects::nonNull).toList();
        // One team only if every unresolved TaskRun is assigned to it.
        Set<Team> unresolvedTeams = failed.stream().filter(f -> f.status() != ResolutionStatus.RESOLVED)
                .map(f -> f.record() == null ? null : f.record().getAssignedTeam()).collect(Collectors.toSet());
        Team team = unresolvedTeams.size() == 1 ? unresolvedTeams.iterator().next() : null;
        Optional<ResolutionRecordEntity> latest = tracked.stream().max(Comparator.comparing(ResolutionRecordEntity::getUpdatedAt));
        Instant resolvedAt = status == ResolutionStatus.RESOLVED
                ? tracked.stream().map(ResolutionRecordEntity::getResolvedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null)
                : null;
        return new ResolutionResponse(ResolutionTargetType.PIPELINE_RUN, null, pipelineRun.getId(), pipelineRun.getPipelineRunName(),
                pipelineRun.getNamespace(), pipelineRun.getStatus(), !tracked.isEmpty(), status, List.of(), team, null,
                summary(failed.size(), resolved), null, latest.map(ResolutionRecordEntity::getUpdatedBy).orElse(null), null,
                latest.map(ResolutionRecordEntity::getUpdatedAt).orElse(null), resolvedAt, null,
                new PipelineTaskRunSummary(failed.size(), resolved, failed.size() - resolved, byStatus, items));
    }

    private static String summary(int failed, int resolved) {
        if (failed == 0) {
            return null;
        }
        return resolved + " of " + failed + " failed TaskRun" + (failed == 1 ? "" : "s") + " resolved";
    }

    private static TaskRunResolutionItem item(FailedTaskRun failed) {
        ResolutionRecordEntity record = failed.record();
        TaskRunEntity taskRun = failed.taskRun();
        return new TaskRunResolutionItem(
                taskRun != null ? taskRun.getId() : record.getTaskRunId(),
                taskRun != null ? taskRun.getTaskRunName() : record.getTargetName(),
                taskRun == null ? null : taskRun.getTaskName(),
                taskRun == null ? null : taskRun.getStatus(),
                failed.status(), record != null,
                record == null ? null : record.getAssignedTeam(), record == null ? null : record.getSummary(),
                record == null ? null : record.getUpdatedAt(), record == null ? null : record.getResolvedAt());
    }

    // ---------------------------------------------------------------- changes

    /** A validated change; text fields are null to keep the current value. */
    private record Change(ResolutionStatus status, Team team, boolean teamGiven, String person, String summary, String notes,
                          String comment, String changedBy) {
    }

    private Change validate(ResolutionUpdateRequest request, ResolutionRecordEntity record, boolean failed) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new InvalidRequestException("status is required: one of " + Arrays.toString(ResolutionStatus.values()));
        }
        ResolutionStatus next = parse(ResolutionStatus.class, request.status(), "status");
        if (!failed) {
            throw new ConflictException("Only failed runs have a resolution; this TaskRun has not failed");
        }
        if (record == null ? request.version() != null : !Long.valueOf(record.getVersion()).equals(request.version())) {
            throw staleVersion(record);
        }
        ResolutionStatus current = record == null ? ResolutionStatus.OPEN : record.getStatus();
        if (!current.canChangeTo(next)) {
            throw new ConflictException("The resolution cannot change from " + current + " to " + next + "; allowed: "
                    + current.nextStatuses());
        }
        boolean teamGiven = request.assignedTeam() != null;
        Team team = teamGiven && !request.assignedTeam().isBlank() ? parse(Team.class, request.assignedTeam(), "assignedTeam") : null;
        return new Change(next, team, teamGiven,
                text(request.assignedPerson(), MAX_NAME, "assignedPerson"),
                text(request.summary(), MAX_SUMMARY, "summary"),
                text(request.notes(), MAX_NOTES, "notes"),
                blankToNull(text(request.comment(), MAX_NOTES, "comment")),
                blankToNull(text(request.changedBy(), MAX_NAME, "changedBy")));
    }

    private ResolutionRecordEntity apply(ResolutionRecordEntity record, Change change) {
        ResolutionStatus previous = record.getId() == null ? null : record.getStatus();
        Instant now = clock.instant();
        record.setStatus(change.status());
        if (change.teamGiven()) {
            record.setAssignedTeam(change.team());
        }
        if (change.person() != null) {
            record.setAssignedPerson(blankToNull(change.person()));
        }
        if (change.summary() != null) {
            record.setSummary(blankToNull(change.summary()));
        }
        if (change.notes() != null) {
            record.setNotes(blankToNull(change.notes()));
        }
        // resolvedAt marks the current resolution: set when it becomes RESOLVED, cleared when it is reopened.
        if (change.status() == ResolutionStatus.RESOLVED) {
            if (previous != ResolutionStatus.RESOLVED) {
                record.setResolvedAt(now);
            }
        } else {
            record.setResolvedAt(null);
        }
        record.setUpdatedBy(change.changedBy());
        record.setUpdatedAt(now);
        ResolutionRecordEntity saved;
        try {
            saved = records.saveAndFlush(record);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            // Someone else created or changed the record between our read and our write.
            throw staleVersion(null);
        }
        history.save(new ResolutionHistoryEntity(saved, previous, change.comment()));
        return saved;
    }

    private static ConflictException staleVersion(ResolutionRecordEntity record) {
        return new ConflictException("The resolution was changed by someone else"
                + (record == null ? "" : " (current version " + record.getVersion() + ")") + "; reload it and try again");
    }

    // ---------------------------------------------------------------- reading

    private ResolutionResponse taskRunResponse(TaskRunEntity taskRun, ResolutionRecordEntity record) {
        if (record == null) {
            ResolutionStatus status = taskRun.getStatus() == ExecutionStatus.FAILED ? ResolutionStatus.OPEN : null;
            return new ResolutionResponse(ResolutionTargetType.TASK_RUN, taskRun.getId(),
                    owningPipelineRun(taskRun).map(PipelineRunEntity::getId).orElse(null), taskRun.getTaskRunName(),
                    taskRun.getNamespace(), taskRun.getStatus(), false, status,
                    status == null ? List.of() : List.copyOf(status.nextStatuses()),
                    null, null, null, null, null, null, null, null, null, null);
        }
        return new ResolutionResponse(ResolutionTargetType.TASK_RUN, taskRun.getId(), record.getPipelineRunId(), record.getTargetName(),
                record.getNamespace(), taskRun.getStatus(), true, record.getStatus(), List.copyOf(record.getStatus().nextStatuses()),
                record.getAssignedTeam(), record.getAssignedPerson(), record.getSummary(), record.getNotes(), record.getUpdatedBy(),
                record.getCreatedAt(), record.getUpdatedAt(), record.getResolvedAt(), record.getVersion(), null);
    }

    private List<ResolutionHistoryEntry> historyOf(List<ResolutionRecordEntity> recordsOfRuns) {
        List<ResolutionHistoryEntry> entries = new ArrayList<>();
        for (ResolutionRecordEntity record : recordsOfRuns) {
            for (ResolutionHistoryEntity e : history.findByResolutionRecordIdOrderByChangedAtAscIdAsc(record.getId())) {
                entries.add(new ResolutionHistoryEntry(e.getId(), record.getTaskRunId(), record.getTargetName(), e.getPreviousStatus(),
                        e.getNewStatus(), e.getAssignedTeam(), e.getAssignedPerson(), e.getSummary(), e.getNote(), e.getChangedBy(),
                        e.getChangedAt()));
            }
        }
        entries.sort(Comparator.comparing(ResolutionHistoryEntry::changedAt).thenComparing(ResolutionHistoryEntry::id));
        return entries;
    }

    // ---------------------------------------------------------------- helpers

    private Optional<ResolutionRecordEntity> find(long taskRunId) {
        return records.findByTargetKey(ResolutionTargetType.TASK_RUN.key(taskRunId));
    }

    private Optional<PipelineRunEntity> owningPipelineRun(TaskRunEntity taskRun) {
        return Optional.ofNullable(taskRun.getPipelineRunUid()).flatMap(pipelineRuns::findByPipelineRunUid);
    }

    private TaskRunEntity loadTaskRun(long id) {
        return taskRuns.findById(id).orElseThrow(() -> new ResourceNotFoundException("TaskRun", id));
    }

    private PipelineRunEntity loadPipelineRun(long id) {
        return pipelineRuns.findById(id).orElseThrow(() -> new ResourceNotFoundException("PipelineRun", id));
    }

    private static Collection<Long> checkBatch(Collection<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_BATCH) {
            throw new InvalidRequestException("ids must contain 1 to " + MAX_BATCH + " ids");
        }
        return ids;
    }

    private static List<Long> sorted(Collection<Long> ids) {
        return Set.copyOf(ids).stream().sorted().toList();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(field + " must be one of " + Arrays.toString(type.getEnumConstants()));
        }
    }

    /** Null stays null (keep), anything else is stripped and must fit. */
    private static String text(String value, int max, String field) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        if (text.length() > max) {
            throw new InvalidRequestException(field + " must be at most " + max + " characters");
        }
        return text;
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
