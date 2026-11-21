package com.hackathon.agent.service;

import com.hackathon.agent.collector.TaskRunSnapshot;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.repository.TaskRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotently persists TaskRun snapshots, matched by Kubernetes UID.
 */
@Service
public class TaskRunUpsertService {

    private static final int LOOKUP_CHUNK_SIZE = 500;

    private final TaskRunRepository repository;
    private final Clock clock;

    public TaskRunUpsertService(TaskRunRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public UpsertResult upsert(List<TaskRunSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return UpsertResult.EMPTY;
        }
        Map<String, TaskRunSnapshot> byUid = snapshots.stream()
                .collect(Collectors.toMap(TaskRunSnapshot::uid, Function.identity(), (a, b) -> b, LinkedHashMap::new));
        Map<String, TaskRunEntity> existing = loadExisting(new ArrayList<>(byUid.keySet()));
        Instant now = Instant.now(clock);

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        List<TaskRunEntity> toSave = new ArrayList<>();
        for (TaskRunSnapshot snapshot : byUid.values()) {
            TaskRunEntity entity = existing.get(snapshot.uid());
            boolean isNew = entity == null;
            if (isNew) {
                entity = new TaskRunEntity();
                entity.setTaskRunUid(snapshot.uid());
            }
            boolean changed = apply(entity, snapshot);
            if (isNew) {
                created++;
            } else if (changed) {
                updated++;
            } else {
                unchanged++;
                continue;
            }
            ExecutionStateUpdater.touch(entity, now, isNew);
            toSave.add(entity);
        }
        repository.saveAll(toSave);
        return new UpsertResult(created, updated, unchanged);
    }

    private boolean apply(TaskRunEntity entity, TaskRunSnapshot snapshot) {
        boolean changed = ExecutionStateUpdater.setIfChanged(entity.getTaskRunName(), snapshot.name(), entity::setTaskRunName);
        changed |= ExecutionStateUpdater.setIfChanged(entity.getNamespace(), snapshot.namespace(), entity::setNamespace);
        changed |= ExecutionStateUpdater.setIfChanged(entity.getPipelineRunUid(), snapshot.pipelineRunUid(), entity::setPipelineRunUid);
        changed |= ExecutionStateUpdater.setIfChanged(entity.getPipelineRunName(), snapshot.pipelineRunName(), entity::setPipelineRunName);
        changed |= ExecutionStateUpdater.setIfChanged(entity.getPipelineName(), snapshot.pipelineName(), entity::setPipelineName);
        changed |= ExecutionStateUpdater.setIfChanged(entity.getTaskName(), snapshot.taskName(), entity::setTaskName);
        changed |= ExecutionStateUpdater.setIfChanged(entity.getResourceCreatedAt(), snapshot.resourceCreatedAt(), entity::setResourceCreatedAt);
        changed |= ExecutionStateUpdater.apply(entity, snapshot.state());
        return changed;
    }

    private Map<String, TaskRunEntity> loadExisting(List<String> uids) {
        Map<String, TaskRunEntity> result = new HashMap<>();
        for (int i = 0; i < uids.size(); i += LOOKUP_CHUNK_SIZE) {
            List<String> chunk = uids.subList(i, Math.min(i + LOOKUP_CHUNK_SIZE, uids.size()));
            repository.findByTaskRunUidIn(chunk).forEach(e -> result.put(e.getTaskRunUid(), e));
        }
        return result;
    }
}
