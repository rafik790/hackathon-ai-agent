package com.hackathon.agent.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hackathon.agent.entity.PipelineRunEntity;
import com.hackathon.agent.entity.TaskRunEntity;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.exception.ConflictException;
import com.hackathon.agent.kubernetes.TektonResourceClient;
import com.hackathon.agent.repository.PipelineRunRepository;
import com.hackathon.agent.repository.TaskRunRepository;
import com.hackathon.agent.resolution.api.ResolutionDtos.ResolutionUpdateRequest;
import com.hackathon.agent.resolution.service.ResolutionService;
import com.hackathon.agent.support.TestEntities;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolution tracking through the API and the V6 schema. Runs on H2 unless AGENT_TEST_DB_URL points to another database.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${AGENT_TEST_DB_URL:jdbc:h2:mem:resolution;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AGENT_TEST_DB_USER:sa}"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResolutionIntegrationTest {

    static final Instant START = Instant.parse("2026-11-28T10:00:00Z");

    @MockitoBean
    TektonResourceClient tekton;

    @Autowired
    MockMvc mvc;
    @Autowired
    ResolutionService service;
    @Autowired
    PipelineRunRepository pipelineRuns;
    @Autowired
    TaskRunRepository taskRuns;
    @Autowired
    JdbcTemplate jdbc;

    final JsonMapper json = JsonMapper.builder().build();
    PipelineRunEntity run;
    TaskRunEntity deploy;
    TaskRunEntity test;
    TaskRunEntity compile;

    @BeforeEach
    void setUp() {
        cleanUp();
        run = pipelineRuns.save(TestEntities.pipelineRun("p1", "ci", "build", ExecutionStatus.FAILED, START, 60_000L));
        deploy = taskRuns.save(TestEntities.taskRun("t1", run, "deploy", ExecutionStatus.FAILED, START.plusSeconds(30), 10_000L));
        test = taskRuns.save(TestEntities.taskRun("t2", run, "test", ExecutionStatus.FAILED, START.plusSeconds(20), 10_000L));
        compile = taskRuns.save(TestEntities.taskRun("t3", run, "compile", ExecutionStatus.SUCCEEDED, START, 10_000L));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from resolution_history");
        jdbc.update("delete from resolution_record");
        taskRuns.deleteAll();
        pipelineRuns.deleteAll();
    }

    // ---------------------------------------------------------------- TaskRuns

    @Test
    void aFailedTaskRunWithoutARecordIsOpenAndAnotherOneHasNoResolution() throws Exception {
        mvc.perform(get("/api/v1/tasks/{id}/resolution", deploy.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracked").value(false))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.executionStatus").value("FAILED"))
                .andExpect(jsonPath("$.pipelineRunId").value(run.getId()))
                .andExpect(jsonPath("$.nextStatuses", contains("INVESTIGATING", "FIX_IN_PROGRESS")))
                .andExpect(jsonPath("$.version").value(nullValue()));
        mvc.perform(get("/api/v1/tasks/{id}/resolution", compile.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(nullValue()))
                .andExpect(jsonPath("$.nextStatuses", hasSize(0)));
        update("tasks", compile.getId(), body("INVESTIGATING", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("has not failed")));
        mvc.perform(get("/api/v1/tasks/{id}/resolution/history", deploy.getId())).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/tasks/{id}/resolution", 999_999)).andExpect(status().isNotFound());
    }

    @Test
    void aTaskRunGoesThroughItsLifecycleAndEveryChangeIsInTheHistory() throws Exception {
        update("tasks", deploy.getId(), """
                {"status": "INVESTIGATING", "version": null, "assignedTeam": "DEVOPS_PLATFORM", "assignedPerson": "Priya",
                 "summary": "Missing RBAC permission", "comment": "Looking at the agent report", "changedBy": "priya"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracked").value(true))
                .andExpect(jsonPath("$.status").value("INVESTIGATING"))
                .andExpect(jsonPath("$.assignedTeam").value("DEVOPS_PLATFORM"))
                .andExpect(jsonPath("$.updatedBy").value("priya"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.resolvedAt").value(nullValue()));
        // Fields that are left out keep their value.
        update("tasks", deploy.getId(), body("FIX_IN_PROGRESS", 0L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedPerson").value("Priya"))
                .andExpect(jsonPath("$.summary").value("Missing RBAC permission"))
                .andExpect(jsonPath("$.version").value(1));
        update("tasks", deploy.getId(), """
                {"status": "RESOLVED", "version": 1, "notes": "Role and RoleBinding applied; rerun succeeded", "comment": "Verified"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.nextStatuses", contains("REOPENED")));

        mvc.perform(get("/api/v1/tasks/{id}/resolution/history", deploy.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].previousStatus").value(nullValue()))
                .andExpect(jsonPath("$[0].newStatus").value("INVESTIGATING"))
                .andExpect(jsonPath("$[0].note").value("Looking at the agent report"))
                .andExpect(jsonPath("$[0].changedBy").value("priya"))
                .andExpect(jsonPath("$[1].previousStatus").value("INVESTIGATING"))
                .andExpect(jsonPath("$[1].newStatus").value("FIX_IN_PROGRESS"))
                .andExpect(jsonPath("$[2].newStatus").value("RESOLVED"))
                .andExpect(jsonPath("$[2].note").value("Verified"));
        assertThat(jdbc.queryForObject("select count(*) from resolution_record", Integer.class)).isEqualTo(1);
        // Resolution needs no AI: nothing was investigated or analysed.
        assertThat(jdbc.queryForObject("select count(*) from agent_investigation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from failure_analysis", Integer.class)).isZero();
    }

    @Test
    void aResolvedIssueCanBeReopenedAndResolvedAgain() throws Exception {
        resolveTaskRun(deploy);
        String resolvedAt = jdbc.queryForObject("select resolved_at from resolution_record", String.class);

        update("tasks", deploy.getId(), "{\"status\": \"REOPENED\", \"version\": 2, \"comment\": \"Failed again in the next run\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REOPENED"))
                .andExpect(jsonPath("$.resolvedAt").value(nullValue()));
        update("tasks", deploy.getId(), body("INVESTIGATING", 3L)).andExpect(status().isOk());
        update("tasks", deploy.getId(), body("RESOLVED", 4L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        assertThat(resolvedAt).isNotNull();
        mvc.perform(get("/api/v1/tasks/{id}/resolution/history", deploy.getId()))
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[3].previousStatus").value("RESOLVED"))
                .andExpect(jsonPath("$[3].newStatus").value("REOPENED"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"status\": \"RESOLVED\"}                                   | 409 | cannot change from OPEN to RESOLVED",
            "{\"status\": \"DONE\"}                                       | 400 | status must be one of",
            "{\"version\": null}                                          | 400 | status is required",
            "{\"status\": \"INVESTIGATING\", \"assignedTeam\": \"QA\"}    | 400 | assignedTeam must be one of",
            "{\"status\": \"INVESTIGATING\", \"version\": 5}              | 409 | changed by someone else",
            "not json                                                     | 400 | not valid JSON"})
    void invalidChangesAreRefused(String body, int status, String message) throws Exception {
        update("tasks", deploy.getId(), body).andExpect(status().is(status)).andExpect(jsonPath("$.message").value(containsString(message)));
        assertThat(jdbc.queryForObject("select count(*) from resolution_record", Integer.class)).isZero();
    }

    @Test
    void aChangeBasedOnAnOldVersionIsRefused() throws Exception {
        update("tasks", deploy.getId(), body("INVESTIGATING", null)).andExpect(status().isOk());
        update("tasks", deploy.getId(), body("FIX_IN_PROGRESS", 0L)).andExpect(status().isOk());

        // Someone else still holds version 0.
        update("tasks", deploy.getId(), body("RESOLVED", 0L)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("current version 1")));
        update("tasks", deploy.getId(), body("INVESTIGATING", null)).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/tasks/{id}/resolution", deploy.getId())).andExpect(jsonPath("$.status").value("FIX_IN_PROGRESS"));
    }

    @Test
    void concurrentChangesNeverOverwriteEachOther() throws Exception {
        assertThat(concurrently(8, () -> service.updateTaskRun(deploy.getId(), request("INVESTIGATING", null)))).isEqualTo(1);
        assertThat(concurrently(8, () -> service.updateTaskRun(deploy.getId(), request("FIX_IN_PROGRESS", 0L)))).isEqualTo(1);

        assertThat(jdbc.queryForObject("select count(*) from resolution_record", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from resolution_record", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from resolution_history", Integer.class)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- PipelineRuns (derived)

    @Test
    void failedTaskRunsWithoutARecordMakeThePipelineRunOpen() throws Exception {
        mvc.perform(get("/api/v1/pipelines/{id}/resolution", run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetType").value("PIPELINE_RUN"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.tracked").value(false))
                .andExpect(jsonPath("$.executionStatus").value("FAILED"))
                .andExpect(jsonPath("$.nextStatuses", hasSize(0)))
                .andExpect(jsonPath("$.version").value(nullValue()))
                .andExpect(jsonPath("$.taskRuns.failedTaskRuns").value(2))
                .andExpect(jsonPath("$.taskRuns.unresolvedTaskRuns").value(2))
                .andExpect(jsonPath("$.taskRuns.byStatus.OPEN").value(2))
                // Ordered by start time; the succeeded TaskRun is not listed.
                .andExpect(jsonPath("$.taskRuns.items[*].taskName", contains("test", "deploy")))
                .andExpect(jsonPath("$.taskRuns.items[*].tracked", contains(false, false)));
    }

    @Test
    void aPipelineRunWithoutFailedTaskRunsHasNothingToResolve() throws Exception {
        PipelineRunEntity other = pipelineRuns.save(TestEntities.pipelineRun("p2", "ci", "build", ExecutionStatus.FAILED, START, 1_000L));
        taskRuns.save(TestEntities.taskRun("t9", other, "compile", ExecutionStatus.SUCCEEDED, START, 1_000L));

        mvc.perform(get("/api/v1/pipelines/{id}/resolution", other.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(nullValue()))
                .andExpect(jsonPath("$.summary").value(nullValue()))
                .andExpect(jsonPath("$.taskRuns.failedTaskRuns").value(0))
                .andExpect(jsonPath("$.taskRuns.items", hasSize(0)));
    }

    @Test
    void thePipelineRunFollowsEveryTaskRunChangeAndIsResolvedWhenTheLastOneIs() throws Exception {
        update("tasks", test.getId(), "{\"status\": \"INVESTIGATING\", \"assignedTeam\": \"DEVELOPER\"}").andExpect(status().isOk());
        expectPipeline("INVESTIGATING", 0, 2);

        update("tasks", deploy.getId(), "{\"status\": \"FIX_IN_PROGRESS\", \"assignedTeam\": \"DEVOPS_PLATFORM\"}").andExpect(status().isOk());
        expectPipeline("FIX_IN_PROGRESS", 0, 2)
                .andExpect(jsonPath("$.tracked").value(true))
                // The unresolved TaskRuns belong to different teams.
                .andExpect(jsonPath("$.assignedTeam").value(nullValue()));

        update("tasks", deploy.getId(), body("RESOLVED", 0L)).andExpect(status().isOk());
        // One resolved TaskRun does not resolve the PipelineRun.
        expectPipeline("INVESTIGATING", 1, 1)
                .andExpect(jsonPath("$.assignedTeam").value("DEVELOPER"))
                .andExpect(jsonPath("$.resolvedAt").value(nullValue()))
                .andExpect(jsonPath("$.summary").value("1 of 2 failed TaskRuns resolved"));

        update("tasks", test.getId(), body("RESOLVED", 0L)).andExpect(status().isOk());
        expectPipeline("RESOLVED", 2, 0)
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.taskRuns.items[1].resolvedAt").isNotEmpty());

        update("tasks", deploy.getId(), "{\"status\": \"REOPENED\", \"version\": 1, \"comment\": \"Failed again\"}").andExpect(status().isOk());
        expectPipeline("REOPENED", 1, 1).andExpect(jsonPath("$.resolvedAt").value(nullValue()));
    }

    @Test
    void aNewFailedTaskRunInAResolvedPipelineRunMakesItUnresolvedAgain() throws Exception {
        resolveTaskRun(deploy);
        resolveTaskRun(test);
        expectPipeline("RESOLVED", 2, 0);

        taskRuns.save(TestEntities.taskRun("t4", run, "integration-test", ExecutionStatus.FAILED, START.plusSeconds(40), 5_000L));

        expectPipeline("OPEN", 2, 1)
                .andExpect(jsonPath("$.taskRuns.items[2].taskName").value("integration-test"))
                .andExpect(jsonPath("$.taskRuns.items[2].tracked").value(false));
    }

    @Test
    void theExecutionStatusAndTheResolutionAreIndependent() throws Exception {
        resolveTaskRun(deploy);
        resolveTaskRun(test);
        run.setStatus(ExecutionStatus.SUCCEEDED);
        pipelineRuns.save(run);

        mvc.perform(get("/api/v1/pipelines/{id}/resolution", run.getId()))
                .andExpect(jsonPath("$.executionStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        mvc.perform(get("/api/v1/tasks/{id}/resolution", deploy.getId()))
                .andExpect(jsonPath("$.executionStatus").value("FAILED"))
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    void thePipelineRunResolutionCannotBeSetByHand() throws Exception {
        update("pipelines", run.getId(), body("RESOLVED", null)).andExpect(status().isMethodNotAllowed());

        assertThat(jdbc.queryForObject("select count(*) from resolution_record", Integer.class)).isZero();
        expectPipeline("OPEN", 0, 2);
    }

    @Test
    void thePipelineRunHistoryHasTheChangesOfItsTaskRuns() throws Exception {
        update("tasks", test.getId(), "{\"status\": \"INVESTIGATING\", \"changedBy\": \"dev\"}").andExpect(status().isOk());
        update("tasks", deploy.getId(), "{\"status\": \"FIX_IN_PROGRESS\", \"comment\": \"Applying the Role\"}").andExpect(status().isOk());
        update("tasks", test.getId(), body("RESOLVED", 0L)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/pipelines/{id}/resolution/history", run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].taskRunName", contains(test.getTaskRunName(), deploy.getTaskRunName(), test.getTaskRunName())))
                .andExpect(jsonPath("$[0].taskRunId").value(test.getId()))
                .andExpect(jsonPath("$[0].changedBy").value("dev"))
                .andExpect(jsonPath("$[1].note").value("Applying the Role"))
                .andExpect(jsonPath("$[2].newStatus").value("RESOLVED"));
    }

    @Test
    void resolutionAndHistoryOutliveTheRunRows() throws Exception {
        resolveTaskRun(deploy);
        long recordId = jdbc.queryForObject("select id from resolution_record", Long.class);

        taskRuns.delete(deploy);

        assertThat(jdbc.queryForMap("select task_run_id, pipeline_run_id, target_name from resolution_record where id = ?", recordId))
                .containsEntry("task_run_id", null)
                .containsEntry("pipeline_run_id", run.getId())
                .containsEntry("target_name", deploy.getTaskRunName());
        assertThat(jdbc.queryForObject("select count(*) from resolution_history where resolution_record_id = ?", Integer.class, recordId))
                .isEqualTo(3);
        expectPipeline("OPEN", 1, 1)
                .andExpect(jsonPath("$.taskRuns.items[1].taskRunName").value(deploy.getTaskRunName()))
                .andExpect(jsonPath("$.taskRuns.items[1].taskRunId").value(nullValue()))
                .andExpect(jsonPath("$.taskRuns.items[1].status").value("RESOLVED"));
        mvc.perform(get("/api/v1/pipelines/{id}/resolution/history", run.getId())).andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void summariesOfSeveralRuns() throws Exception {
        update("tasks", deploy.getId(), body("INVESTIGATING", null)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/tasks/resolutions").param("ids", deploy.getId() + "," + test.getId() + "," + compile.getId() + ",999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].status").value("INVESTIGATING"))
                .andExpect(jsonPath("$[0].tracked").value(true))
                .andExpect(jsonPath("$[1].status").value("OPEN"))
                .andExpect(jsonPath("$[2].status").value(nullValue()));
        mvc.perform(get("/api/v1/pipelines/resolutions").param("ids", String.valueOf(run.getId())))
                .andExpect(jsonPath("$[0].status").value("INVESTIGATING"))
                .andExpect(jsonPath("$[0].tracked").value(true));
        mvc.perform(get("/api/v1/tasks/resolutions")).andExpect(status().isBadRequest());
    }

    private ResultActions expectPipeline(String status, int resolved, int unresolved) throws Exception {
        return mvc.perform(get("/api/v1/pipelines/{id}/resolution", run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.taskRuns.resolvedTaskRuns").value(resolved))
                .andExpect(jsonPath("$.taskRuns.unresolvedTaskRuns").value(unresolved));
    }

    // ---------------------------------------------------------------- dashboard (read-only)

    @Test
    void theDashboardSummaryCountsTheFailedTaskRunsOfTheWindowByResolution() throws Exception {
        // Outside the window and in another namespace: not counted.
        taskRuns.save(TestEntities.taskRun("t-old", run, "old", ExecutionStatus.FAILED, START.minusSeconds(86_400 * 30), 1_000L));
        PipelineRunEntity other = pipelineRuns.save(TestEntities.pipelineRun("p-o", "staging", "build", ExecutionStatus.FAILED, START, 1_000L));
        taskRuns.save(TestEntities.taskRun("t-o", other, "deploy", ExecutionStatus.FAILED, START, 1_000L));
        update("tasks", deploy.getId(), body("INVESTIGATING", null)).andExpect(status().isOk());

        summary("ci").andExpect(jsonPath("$.failedTaskRuns").value(2))
                .andExpect(jsonPath("$.failedTaskRunsByResolution.OPEN").value(1))
                .andExpect(jsonPath("$.failedTaskRunsByResolution.INVESTIGATING").value(1))
                .andExpect(jsonPath("$.failedTaskRunsByResolution.FIX_IN_PROGRESS").value(0))
                .andExpect(jsonPath("$.failedTaskRunsByResolution.RESOLVED").value(0))
                .andExpect(jsonPath("$.failedTaskRunsByResolution.REOPENED").value(0));

        update("tasks", deploy.getId(), body("RESOLVED", 0L)).andExpect(status().isOk());
        update("tasks", test.getId(), body("FIX_IN_PROGRESS", null)).andExpect(status().isOk());
        summary("ci").andExpect(jsonPath("$.failedTaskRunsByResolution.OPEN").value(0))
                .andExpect(jsonPath("$.failedTaskRunsByResolution.FIX_IN_PROGRESS").value(1))
                .andExpect(jsonPath("$.failedTaskRunsByResolution.RESOLVED").value(1));
        update("tasks", deploy.getId(), body("REOPENED", 1L)).andExpect(status().isOk());
        summary("ci").andExpect(jsonPath("$.failedTaskRunsByResolution.REOPENED").value(1))
                .andExpect(jsonPath("$.failedTaskRunsByResolution.RESOLVED").value(0));
        // All namespaces: the staging failure counts as OPEN, since it has no record.
        summary(null).andExpect(jsonPath("$.failedTaskRuns").value(3))
                .andExpect(jsonPath("$.failedTaskRunsByResolution.OPEN").value(1));
    }

    @Test
    void pipelineRunSummariesCarryTheirTaskRunCounts() throws Exception {
        resolveTaskRun(deploy);

        mvc.perform(get("/api/v1/pipelines/resolutions").param("ids", String.valueOf(run.getId())))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].failedTaskRuns").value(2))
                .andExpect(jsonPath("$[0].resolvedTaskRuns").value(1));
        mvc.perform(get("/api/v1/tasks/resolutions").param("ids", String.valueOf(deploy.getId())))
                .andExpect(jsonPath("$[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$[0].failedTaskRuns").value(nullValue()));
    }

    private ResultActions summary(String namespace) throws Exception {
        var request = get("/api/v1/dashboard/summary").param("from", START.minusSeconds(3_600).toString())
                .param("to", START.plusSeconds(3_600).toString());
        return mvc.perform(namespace == null ? request : request.param("namespace", namespace)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private void resolveTaskRun(TaskRunEntity taskRun) throws Exception {
        update("tasks", taskRun.getId(), body("INVESTIGATING", null)).andExpect(status().isOk());
        update("tasks", taskRun.getId(), body("FIX_IN_PROGRESS", 0L)).andExpect(status().isOk());
        update("tasks", taskRun.getId(), body("RESOLVED", 1L)).andExpect(status().isOk());
    }

    private ResultActions update(String resource, long id, String body) throws Exception {
        return mvc.perform(put("/api/v1/{resource}/{id}/resolution", resource, id).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String body(String status, Long version) {
        return json.writeValueAsString(request(status, version));
    }

    private static ResolutionUpdateRequest request(String status, Long version) {
        return new ResolutionUpdateRequest(status, version, null, null, null, null, null, null);
    }

    /** Runs the same change in parallel; returns how many succeeded. Every other one must be refused as a conflict. */
    private static int concurrently(int threads, Runnable change) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(executor.submit(() -> {
                start.await();
                try {
                    change.run();
                    return true;
                } catch (ConflictException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> result : results) {
            succeeded += result.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        executor.shutdown();
        return succeeded;
    }
}
