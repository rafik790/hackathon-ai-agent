package com.hackathon.agent.investigation.repository;

import com.hackathon.agent.investigation.entity.AgentInvestigationEntity;
import com.hackathon.agent.investigation.model.InvestigationModel.InvestigationStatus;
import com.hackathon.agent.investigation.model.InvestigationModel.TargetType;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentInvestigationRepository
        extends JpaRepository<AgentInvestigationEntity, Long>, JpaSpecificationExecutor<AgentInvestigationEntity> {

    Optional<AgentInvestigationEntity> findFirstByStatusOrderByIdAsc(InvestigationStatus status);

    Optional<AgentInvestigationEntity> findFirstByNamespaceAndTargetTypeAndTargetNameAndStatusInOrderByIdDesc(
            String namespace, TargetType targetType, String targetName, Collection<InvestigationStatus> statuses);

    /** Claims a pending investigation; only one worker of all instances can succeed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentInvestigationEntity i
               set i.status = com.hackathon.agent.investigation.model.InvestigationModel.InvestigationStatus.RUNNING,
                   i.claimToken = :token, i.leaseUntil = :leaseUntil, i.startedAt = :now, i.updatedAt = :now
             where i.id = :id
               and i.status = com.hackathon.agent.investigation.model.InvestigationModel.InvestigationStatus.PENDING
            """)
    int claim(@Param("id") Long id, @Param("token") String token, @Param("leaseUntil") Instant leaseUntil,
              @Param("now") Instant now);

    /** Running investigations whose worker disappeared, e.g. because the application was killed. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AgentInvestigationEntity i
               set i.status = com.hackathon.agent.investigation.model.InvestigationModel.InvestigationStatus.FAILED,
                   i.errorMessage = :message, i.claimToken = null, i.leaseUntil = null,
                   i.completedAt = :now, i.updatedAt = :now
             where i.status = com.hackathon.agent.investigation.model.InvestigationModel.InvestigationStatus.RUNNING
               and i.leaseUntil < :now
            """)
    int failAbandoned(@Param("now") Instant now, @Param("message") String message);
}
