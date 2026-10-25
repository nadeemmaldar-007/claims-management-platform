package com.apac.claims.reporting.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The work of one active officer. Every active officer of the scope has a row, also one with no claims. The age of a claim
 * is the time since it was submitted.
 */
@Schema(description = "The workload of one officer.")
public record OfficerWorkload(
        @Schema(description = "User id of the officer.", example = "officer.in") String userId,
        @Schema(description = "Full name of the officer.", example = "Officer India") String fullName,
        @Schema(description = "Market of the officer.", example = "IN") String marketCode,
        @Schema(description = "Open claims the officer has: UNDER_REVIEW plus PENDING_INFORMATION plus ESCALATED.",
                example = "3") long openAssigned,
        @Schema(description = "Claims UNDER_REVIEW.", example = "2") long underReview,
        @Schema(description = "Claims PENDING_INFORMATION.", example = "1") long pendingInformation,
        @Schema(description = "Claims ESCALATED that are still assigned to the officer.", example = "0") long escalated,
        @Schema(description = "Claims the officer has that are APPROVED and wait for settlement.", example = "1")
        long approvedAwaitingSettlement,
        @Schema(description = "Age in whole hours, since submission, of the oldest open claim the officer has; null "
                + "when the officer has none.", example = "72", nullable = true) Long oldestOpenAgeHours,
        @Schema(description = "Open claims the officer has that are older than the decision target "
                + "(app.sla.decision-hours, 240 hours by default).", example = "0") long slaBreaches) {
}
