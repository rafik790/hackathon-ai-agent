package com.hackathon.agent.ai.repository;

import com.hackathon.agent.ai.entity.AiQuotaEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiQuotaRepository extends JpaRepository<AiQuotaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from AiQuotaEntity q where q.provider = :provider")
    Optional<AiQuotaEntity> lock(@Param("provider") String provider);
}
