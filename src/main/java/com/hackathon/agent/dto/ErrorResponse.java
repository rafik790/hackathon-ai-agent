package com.hackathon.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "Error returned by every endpoint for 4xx/5xx responses")
public record ErrorResponse(
        @Schema(example = "2026-11-21T10:15:30Z") Instant timestamp,
        @Schema(example = "400") int status,
        @Schema(example = "Bad Request") String error,
        @Schema(example = "'from' must be before 'to'") String message,
        @Schema(example = "/api/v1/dashboard/summary") String path,
        @Schema(description = "Per-parameter validation errors, empty when not applicable") List<FieldError> fieldErrors) {

    public record FieldError(
            @Schema(example = "size") String field,
            @Schema(example = "must be less than or equal to 100") String message) {
    }
}
