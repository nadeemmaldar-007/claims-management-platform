package com.apac.claims.reporting.api.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** The workload report: what every officer has on the desk, and the queue of every market in scope. */
@Schema(description = "Workload per officer and queue per market.")
public record WorkloadResponse(
        @Schema(description = "One row per active officer in scope.") List<OfficerWorkload> officers,
        @Schema(description = "One row per market in scope.") List<MarketQueue> markets) {
}
