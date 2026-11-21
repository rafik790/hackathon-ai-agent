package com.hackathon.agent.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hackathon.agent.config.ClockConfig;
import com.hackathon.agent.config.CorsProperties;
import com.hackathon.agent.dto.DashboardSummaryResponse;
import com.hackathon.agent.dto.TrendResponse;
import com.hackathon.agent.enums.TrendGranularity;
import com.hackathon.agent.exception.InvalidRequestException;
import com.hackathon.agent.service.DashboardService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DashboardController.class)
@Import(ClockConfig.class)
@EnableConfigurationProperties(CorsProperties.class)
class DashboardControllerTest {

    private static final Instant FROM = Instant.parse("2026-11-14T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-21T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DashboardService dashboardService;

    @Test
    void returnsSummary() throws Exception {
        when(dashboardService.summary(eq("7d"), isNull(), isNull(), eq("ci"), isNull())).thenReturn(
                new DashboardSummaryResponse(FROM, TO, 120, 102, 15, 3, 0, 87.18, 12.82, 184_250L, 402_000L,
                        560, 18, 3.23));

        mvc.perform(get("/api/v1/dashboard/summary").param("range", "7d").param("namespace", "ci"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-11-14T10:00:00Z"))
                .andExpect(jsonPath("$.totalExecutions").value(120))
                .andExpect(jsonPath("$.successRate").value(87.18))
                .andExpect(jsonPath("$.p95DurationMs").value(402000))
                .andExpect(jsonPath("$.taskFailureRate").value(3.23));
    }

    @Test
    void emptySummaryHasNullDurations() throws Exception {
        when(dashboardService.summary(any(), any(), any(), any(), any())).thenReturn(
                new DashboardSummaryResponse(FROM, TO, 0, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0));

        mvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageDurationMs").value(nullValue()))
                .andExpect(jsonPath("$.p95DurationMs").value(nullValue()))
                .andExpect(jsonPath("$.successRate").value(0.0));
    }

    @Test
    void invalidRangeReturns400() throws Exception {
        when(dashboardService.summary(eq("1y"), any(), any(), any(), any()))
                .thenThrow(new InvalidRequestException("Unsupported range '1y'. Supported values: 24h, 7d, 30d"));

        mvc.perform(get("/api/v1/dashboard/summary").param("range", "1y"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported range '1y'. Supported values: 24h, 7d, 30d"));
    }

    @Test
    void returnsTrendWithGranularity() throws Exception {
        when(dashboardService.trends(isNull(), eq(FROM), eq(TO), eq(TrendGranularity.DAY), isNull(), isNull()))
                .thenReturn(new TrendResponse(FROM, TO, TrendGranularity.DAY, List.of(
                        new TrendResponse.Point(FROM, 5, 4, 1, 0, 0, 80.0, 171_000L))));

        mvc.perform(get("/api/v1/dashboard/trends")
                        .param("from", "2026-11-14T10:00:00Z").param("to", "2026-11-21T10:00:00Z")
                        .param("granularity", "day"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("DAY"))
                .andExpect(jsonPath("$.points[0].bucketStart").value("2026-11-14T10:00:00Z"))
                .andExpect(jsonPath("$.points[0].successRate").value(80.0));
    }

    @Test
    void invalidGranularityReturns400() throws Exception {
        mvc.perform(get("/api/v1/dashboard/trends").param("granularity", "week"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("granularity"));
    }
}
