package com.hackathon.agent.resolution.repository;

import com.hackathon.agent.resolution.entity.ResolutionHistoryEntity;
import java.util.List;
import org.springframework.data.repository.Repository;

/** Append-only: entries can be saved and read, never updated through the application or deleted. */
public interface ResolutionHistoryRepository extends Repository<ResolutionHistoryEntity, Long> {

    ResolutionHistoryEntity save(ResolutionHistoryEntity entry);

    /** Oldest first. */
    List<ResolutionHistoryEntity> findByResolutionRecordIdOrderByChangedAtAscIdAsc(Long resolutionRecordId);
}
