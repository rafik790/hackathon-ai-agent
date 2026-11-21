package com.hackathon.agent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hackathon.agent.config.ClockConfig;
import com.hackathon.agent.config.CorsProperties;
import com.hackathon.agent.dto.PageResponse;
import com.hackathon.agent.dto.TaskRunResponse;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.exception.ResourceNotFoundException;
import com.hackathon.agent.service.TaskRunQueryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TaskRunController.class)
@Import(ClockConfig.class)
@EnableConfigurationProperties(CorsProperties.class)
class TaskRunControllerTest {

    private static final Instant START = Instant.parse("2026-11-21T09:59:40Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TaskRunQueryService queryService;

    @Test
    void listsTaskRuns() throws Exception {
        when(queryService.find(any(), eq("unit-tests"), any(), any())).thenReturn(new PageResponse<>(
                List.of(task()), 0, 20, 1, 1, true, true, "startTime: DESC"));

        mvc.perform(get("/api/v1/tasks").param("taskName", "unit-tests").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].taskName").value("unit-tests"))
                .andExpect(jsonPath("$.content[0].failureMessage").value("\"step-test\" exited with code 1"))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void returnsTaskRunDetails() throws Exception {
        when(queryService.get(311L)).thenReturn(task());

        mvc.perform(get("/api/v1/tasks/311"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskRunUid").value("t-311"));
    }

    @Test
    void unknownTaskRunReturns404() throws Exception {
        when(queryService.get(9L)).thenThrow(new ResourceNotFoundException("TaskRun", 9L));

        mvc.perform(get("/api/v1/tasks/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TaskRun with id 9 not found"));
    }

    private static TaskRunResponse task() {
        return new TaskRunResponse(311L, "t-311", "build-x-unit-tests", "ci", "uid-42", "build-x", "build",
                "unit-tests", ExecutionStatus.FAILED, START, START.plusSeconds(92), 92_000L, "Failed",
                "\"step-test\" exited with code 1", START, START, START);
    }
}
