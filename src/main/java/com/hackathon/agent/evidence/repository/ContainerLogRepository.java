package com.hackathon.agent.evidence.repository;

import com.hackathon.agent.evidence.entity.ContainerLogEntity;
import com.hackathon.agent.evidence.model.StoredLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContainerLogRepository extends JpaRepository<ContainerLogEntity, Long> {

    List<ContainerLogEntity> findByContainerEvidenceId(Long containerEvidenceId);

    /** Log outcomes of one attempt without loading the log text. */
    @Query("""
            select new com.hackathon.agent.evidence.model.StoredLog(
                c.containerName, l.previous, l.status, coalesce(l.storedBytes, 0))
            from ContainerLogEntity l join ContainerEvidenceEntity c on c.id = l.containerEvidenceId
            where c.evidenceId = :evidenceId
            """)
    List<StoredLog> findStoredLogs(@Param("evidenceId") Long evidenceId);
}
