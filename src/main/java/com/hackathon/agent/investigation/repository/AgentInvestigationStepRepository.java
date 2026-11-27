package com.hackathon.agent.investigation.repository;

import com.hackathon.agent.investigation.entity.AgentInvestigationStepEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentInvestigationStepRepository extends JpaRepository<AgentInvestigationStepEntity, Long> {

    List<AgentInvestigationStepEntity> findByInvestigationIdOrderByStepNumber(Long investigationId);
}
