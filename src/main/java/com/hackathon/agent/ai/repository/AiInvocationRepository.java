package com.hackathon.agent.ai.repository;

import com.hackathon.agent.ai.entity.AiInvocationEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiInvocationRepository extends JpaRepository<AiInvocationEntity, Long> {

    long countByProviderAndInvokedAtGreaterThan(String provider, Instant since);

    /** Start of the oldest invocation that still counts; null when there is none. */
    @Query("select min(i.invokedAt) from AiInvocationEntity i where i.provider = :provider and i.invokedAt > :since")
    Instant findOldest(@Param("provider") String provider, @Param("since") Instant since);
}
