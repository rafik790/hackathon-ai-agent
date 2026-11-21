package com.hackathon.agent.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hackathon.agent.config.ClockConfig;
import com.hackathon.agent.config.CorsProperties;
import com.hackathon.agent.dto.PageResponse;
import com.hackathon.agent.dto.PipelineRunResponse;
import com.hackathon.agent.dto.TaskRunResponse;
import com.hackathon.agent.enums.ExecutionStatus;
import com.hackathon.agent.exception.InvalidRequestException;
import com.hackathon.agent.exception.ResourceNotFoundException;
import com.hackathon.agent.repository.ExecutionFilter;
import com.hackathon.agent.service.PipelineRunQueryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PipelineRunController.class)
@Import(ClockConfig.class)
@EnableConfigurationProperties(CorsProperties.class)
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:4200")
class PipelineRunControllerTest {

    private static final Instant START = Instant.parse("2026-11-21T09:58:02Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PipelineRunQueryService queryService;

    @Test
    void listsPipelineRunsWithPaginationMetadata() throws Exception {
        when(queryService.find(any(), any())).thenReturn(new PageResponse<>(List.of(response()), 0, 20, 1, 1,
                true, true, "startTime: DESC"));

        mvc.perform(get("/api/v1/pipelines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(42))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].startTime").value("2026-11-21T09:58:02Z"))
                .andExpect(jsonPath("$.content[0].durationMs").value(195000))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void passesFiltersPaginationAndSortToService() throws Exception {
        when(queryService.find(any(), any())).thenReturn(new PageResponse<>(List.of(), 2, 5, 0, 0, false, true, null));

        mvc.perform(get("/api/v1/pipelines")
                        .param("namespace", "ci").param("pipelineName", "build").param("status", "failed")
                        .param("from", "2026-11-20T00:00:00Z").param("to", "2026-11-21T00:00:00Z")
                        .param("page", "2").param("size", "5").param("sort", "durationMs,asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<ExecutionFilter> filter = ArgumentCaptor.forClass(ExecutionFilter.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(queryService).find(filter.capture(), pageable.capture());
        org.assertj.core.api.Assertions.assertThat(filter.getValue()).isEqualTo(new ExecutionFilter("ci", "build",
                ExecutionStatus.FAILED, Instant.parse("2026-11-20T00:00:00Z"), Instant.parse("2026-11-21T00:00:00Z")));
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "durationMs"));
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        when(queryService.find(any(), any())).thenReturn(new PageResponse<>(List.of(), 0, 100, 0, 0, true, true, null));

        mvc.perform(get("/api/v1/pipelines").param("size", "5000")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(queryService).find(any(), pageable.capture());
        org.assertj.core.api.Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void invalidStatusReturns400WithFieldError() throws Exception {
        mvc.perform(get("/api/v1/pipelines").param("status", "BROKEN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/api/v1/pipelines"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"))
                .andExpect(jsonPath("$.message", containsString("BROKEN")));
    }

    @Test
    void invalidDateReturns400() throws Exception {
        mvc.perform(get("/api/v1/pipelines").param("from", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"));
    }

    @Test
    void serviceValidationErrorReturns400() throws Exception {
        when(queryService.find(any(), any())).thenThrow(new InvalidRequestException("Unsupported sort property 'foo'"));

        mvc.perform(get("/api/v1/pipelines").param("sort", "foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported sort property 'foo'"));
    }

    @Test
    void returnsPipelineRunDetails() throws Exception {
        when(queryService.get(42L)).thenReturn(response());

        mvc.perform(get("/api/v1/pipelines/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipelineRunUid").value("uid-42"))
                .andExpect(jsonPath("$.failureReason").value("Failed"));
    }

    @Test
    void unknownPipelineRunReturns404() throws Exception {
        when(queryService.get(7L)).thenThrow(new ResourceNotFoundException("PipelineRun", 7L));

        mvc.perform(get("/api/v1/pipelines/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("PipelineRun with id 7 not found"));
    }

    @Test
    void nonNumericIdReturns400() throws Exception {
        mvc.perform(get("/api/v1/pipelines/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void returnsTaskRunsOfPipelineRun() throws Exception {
        when(queryService.tasksOf(eq(42L))).thenReturn(List.of(new TaskRunResponse(1L, "t-1", "build-clone", "ci",
                "uid-42", "build-x", "build", "clone", ExecutionStatus.SUCCEEDED, START, START.plusSeconds(5), 5000L,
                null, null, START, START, START)));

        mvc.perform(get("/api/v1/pipelines/42/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskName").value("clone"))
                .andExpect(jsonPath("$[0].durationMs").value(5000));
    }

    @Test
    void unexpectedErrorReturns500WithoutInternals() throws Exception {
        when(queryService.get(1L)).thenThrow(new IllegalStateException("secret internal detail"));

        mvc.perform(get("/api/v1/pipelines/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unexpected server error"));
    }

    @Test
    void corsPreflightAllowsConfiguredFrontendOrigin() throws Exception {
        mvc.perform(options("/api/v1/pipelines")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));

        mvc.perform(options("/api/v1/pipelines")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    private static PipelineRunResponse response() {
        return new PipelineRunResponse(42L, "uid-42", "build-x", "ci", "build", ExecutionStatus.FAILED, START,
                START.plusMillis(195_000), 195_000L, "Failed", "Tasks Completed: 3 (Failed: 1)",
                START, START, START);
    }
}
