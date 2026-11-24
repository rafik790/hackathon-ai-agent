package com.hackathon.agent.ai;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs on H2 unless AI_TEST_DB_URL points to another database.
 */
@SpringBootTest(properties = {
        "ai.enabled=true", "ai.provider=mock",
        // The scheduler is started but must not interfere; tests run cycles themselves.
        "ai.analysis.initial-delay-ms=86400000",
        "spring.datasource.url=${AI_TEST_DB_URL:jdbc:h2:mem:analysis;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AI_TEST_DB_USER:sa}"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FailureAnalysisIntegrationTest extends AbstractFailureAnalysisIntegrationTest {
}
