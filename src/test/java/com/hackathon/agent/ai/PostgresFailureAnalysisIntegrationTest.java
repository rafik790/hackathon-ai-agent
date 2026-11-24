package com.hackathon.agent.ai;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The same tests on PostgreSQL 17. Skipped automatically when Docker is not available.
 */
@SpringBootTest(properties = {
        "ai.enabled=true", "ai.provider=mock",
        // The scheduler is started but must not interfere; tests run cycles themselves.
        "ai.analysis.initial-delay-ms=86400000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostgresFailureAnalysisIntegrationTest extends AbstractFailureAnalysisIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
}
