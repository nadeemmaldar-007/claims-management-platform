package com.apac.claims.reporting.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The queue of one market: the claims nobody has taken yet. */
@Schema(description = "The queue of one market.")
public record MarketQueue(
        @Schema(description = "Market code.", example = "IN") String marketCode,
        @Schema(description = "Claims that are SUBMITTED and not assigned.", example = "2") long unassignedInQueue,
        @Schema(description = "Queued claims older than the first-review target (app.sla.first-review-hours, 48 hours "
                + "by default), counted from submission.", example = "1") long queueSlaBreaches) {
}
