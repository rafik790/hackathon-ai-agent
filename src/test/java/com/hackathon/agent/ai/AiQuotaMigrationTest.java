package com.hackathon.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Upgrading must not reset the quota: what V3 counted is carried over.
 */
class AiQuotaMigrationTest {

    private static final String NOW = "TIMESTAMP WITH TIME ZONE '2026-11-25 12:00:00+00'";

    @Test
    void carriesOverStartedAnalysesExceptMockOnes() {
        // One connection for everything: H2 evaluates check constraints in the session that created them.
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "", true);
        migrate(dataSource, "3");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into task_run(task_run_uid, task_run_name, namespace, status, created_at, updated_at) "
                + "values ('u', 'n', 'ci', 'FAILED', " + NOW + ", " + NOW + ")");
        jdbc.update("insert into failure_evidence(task_run_id, attempt, status, created_at, updated_at) "
                + "select id, 0, 'COLLECTED', " + NOW + ", " + NOW + " from task_run");
        analysis(jdbc, 0, "'anthropic'", NOW);
        analysis(jdbc, 1, "'anthropic'", NOW);
        analysis(jdbc, 2, "'mock'", NOW);
        analysis(jdbc, 3, "null", "null");

        migrate(dataSource, "4");

        assertThat(jdbc.queryForList("select provider from ai_quota", String.class)).containsExactly("anthropic");
        assertThat(jdbc.queryForObject("select count(*) from ai_invocation where provider = 'anthropic' and failure_analysis_id is not null "
                + "and invoked_at = " + NOW, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from ai_invocation", Integer.class)).isEqualTo(2);
        dataSource.destroy();
    }

    private static void analysis(JdbcTemplate jdbc, int revision, String provider, String startedAt) {
        jdbc.update("insert into failure_analysis(failure_evidence_id, analysis_version, prompt_version, revision, analysis_status, "
                + "provider, started_at, created_at, updated_at) select id, 1, 'v1', " + revision + ", 'FAILED', " + provider + ", "
                + startedAt + ", " + NOW + ", " + NOW + " from failure_evidence");
    }

    private static void migrate(DataSource dataSource, String version) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(version).load().migrate();
    }
}
