package com.hackathon.agent.evidence.repository;

import com.hackathon.agent.evidence.entity.ContainerEvidenceEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContainerEvidenceRepository extends JpaRepository<ContainerEvidenceEntity, Long> {

    List<ContainerEvidenceEntity> findByEvidenceId(Long evidenceId);
}
