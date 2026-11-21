package com.hackathon.agent.service;

import com.hackathon.agent.collector.PipelineRunSnapshot;
import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.repository.PipelineRunRepository;
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
 * Idempotently persists PipelineRun snapshots: records are matched by Kubernetes UID, created when new,
 * updated only when something changed and otherwise left untouched.
 */
@Service
public class PipelineRunUpsertService {

    private static final int LOOKUP_CHUNK_SIZE = 500;

    private final PipelineRunRepository repository;
    private final Clock clock;

    public PipelineRunUpsertService(PipelineRunRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public UpsertResult upsert(List<PipelineRunSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return UpsertResult.EMPTY;
        }
        // De-duplicate by UID in case the API returned the same object twice across pages.
        Map<String, PipelineRunSnapshot> byUid = snapshots.stream()
                .collect(Collectors.toMap(PipelineRunSnapshot::uid, Function.identity(), (a, b) -> b, LinkedHashMap::new));
        Map<String, PipelineRunEntity> existing = loadExisting(new ArrayList<>(byUid.keySet()));
        Instant now = Instant.now(clock);

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        List<PipelineRunEntity> toSave = new ArrayList<>();
        for (PipelineRunSnapshot snapshot : byUid.values()) {
            PipelineRunEntity entity = existing.get(snapshot.uid());
            boolean isNew = entity == null;
            if (isNew) {
                entity = new PipelineRunEntity();
                entity.setPipelineRunUid(snapshot.uid());
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

    private boolean apply(PipelineRunEntity entity, PipelineRunSnapshot snapshot) {
        boolean changed = ExecutionStateUpdater.setIfChanged(entity.getPipelineRunName(), snapshot.name(), entity::setPipelineRunName);
        changed |= ExecutionStateUpdater.setIfChanged(entity.getNamespace(), snapshot.namespace(), entity::setNamespace);
        changed |= ExecutionStateUpdater.setIfChanged(entity.getPipelineName(), snapshot.pipelineName(), entity::setPipelineName);
        changed |= ExecutionStateUpdater.setIfChanged(entity.getResourceCreatedAt(), snapshot.resourceCreatedAt(), entity::setResourceCreatedAt);
        changed |= ExecutionStateUpdater.apply(entity, snapshot.state());
        return changed;
    }

    private Map<String, PipelineRunEntity> loadExisting(List<String> uids) {
        Map<String, PipelineRunEntity> result = new HashMap<>();
        for (int i = 0; i < uids.size(); i += LOOKUP_CHUNK_SIZE) {
            List<String> chunk = uids.subList(i, Math.min(i + LOOKUP_CHUNK_SIZE, uids.size()));
            repository.findByPipelineRunUidIn(chunk).forEach(e -> result.put(e.getPipelineRunUid(), e));
        }
        return result;
    }
}
