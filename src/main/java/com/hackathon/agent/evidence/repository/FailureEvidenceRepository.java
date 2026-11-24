package com.hackathon.agent.evidence.repository;

import com.hackathon.agent.evidence.entity.FailureEvidenceEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FailureEvidenceRepository extends JpaRepository<FailureEvidenceEntity, Long> {

    List<FailureEvidenceEntity> findByTaskRunIdOrderByAttempt(Long taskRunId);
}
