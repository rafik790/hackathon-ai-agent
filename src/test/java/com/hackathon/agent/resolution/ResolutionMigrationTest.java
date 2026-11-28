package com.hackathon.agent.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V6 on top of an existing V5 database: existing data stays, and the constraints keep one record per run and a
 * consistent resolved_at.
 */
class ResolutionMigrationTest {

    private static final String NOW = "TIMESTAMP WITH TIME ZONE '2026-11-28 12:00:00+00'";

    @Test
    void addsTheResolutionTablesWithoutTouchingExistingData() {
        // One connection for everything: H2 evaluates check constraints in the session that created them.
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:resolution-migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "sa", "", true);
        migrate(dataSource, "5");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into task_run(task_run_uid, task_run_name, namespace, status, created_at, updated_at) "
                + "values ('u', 'build-1', 'ci', 'FAILED', " + NOW + ", " + NOW + ")");
        jdbc.update("insert into agent_investigation(target_type, target_name, namespace, task_run_id, status, created_at, updated_at) "
                + "select 'TASK_RUN', 'build-1', 'ci', id, 'COMPLETED', " + NOW + ", " + NOW + " from task_run");

        migrate(dataSource, "6");

        assertThat(jdbc.queryForObject("select count(*) from task_run", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from agent_investigation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from resolution_record", Integer.class)).isZero();
        record(jdbc, "'INVESTIGATING'", "null");
        assertThatThrownBy(() -> record(jdbc, "'INVESTIGATING'", "null")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("delete from resolution_record");
        assertThatThrownBy(() -> record(jdbc, "'RESOLVED'", "null")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> record(jdbc, "'INVESTIGATING'", NOW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> record(jdbc, "'DONE'", "null")).isInstanceOf(DataIntegrityViolationException.class);
        record(jdbc, "'RESOLVED'", NOW);
        dataSource.destroy();
    }

    private static void record(JdbcTemplate jdbc, String status, String resolvedAt) {
        jdbc.update("insert into resolution_record(target_type, target_key, task_run_id, target_name, namespace, status, "
                + "created_at, updated_at, resolved_at) select 'TASK_RUN', 'TASK_RUN:' || id, id, 'build-1', 'ci', " + status
                + ", " + NOW + ", " + NOW + ", " + resolvedAt + " from task_run");
    }

    private static void migrate(DataSource dataSource, String version) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(version).load().migrate();
    }
}
