package com.apac.claims.reporting.api.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** The performance report for a period of whole days, both ends included, in UTC. */
@Schema(description = "Performance per officer for a period.")
public record PerformanceResponse(
        @Schema(description = "First day of the period (UTC), inclusive.", example = "2026-09-26") LocalDate from,
        @Schema(description = "Last day of the period (UTC), inclusive.", example = "2026-10-25") LocalDate to,
        @Schema(description = "One row per active officer in scope.") List<OfficerPerformance> officers) {
}
