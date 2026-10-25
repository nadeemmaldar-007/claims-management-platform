package com.apac.claims.reporting.api.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The results of one officer in a period. Only officers have a row: a decision taken by a manager belongs to no row.
 * A person can have a row with zeros and no averages when nothing fell into the period.
 */
@Schema(description = "The performance of one officer in the period.")
public record OfficerPerformance(
        @Schema(description = "User id of the officer.", example = "officer.in") String userId,
        @Schema(description = "Full name of the officer.", example = "Officer India") String fullName,
        @Schema(description = "Market of the officer.", example = "IN") String marketCode,
        @Schema(description = "Claims the officer approved or rejected in the period (decision time inside the "
                + "period).", example = "5") long decidedCount,
        @Schema(description = "Of those, the approved ones.", example = "3") long approvedCount,
        @Schema(description = "Of those, the rejected ones.", example = "2") long rejectedCount,
        @Schema(description = "approvedCount divided by decidedCount, between 0 and 1 with four decimals; null when "
                + "there is no decision.", example = "0.6000", nullable = true) BigDecimal approvalRate,
        @Schema(description = "Claims assigned to the officer that were settled in the period.", example = "1")
        long settledCount,
        @Schema(description = "Average hours from submission to assignment, over the claims assigned to the officer in "
                + "the period (a reassignment counts from the new assignment); null when there is none.",
                example = "5.25", nullable = true) BigDecimal avgHoursToFirstReview,
        @Schema(description = "Average hours from submission to the decision, over the claims the officer decided in "
                + "the period; null when there is none.", example = "30.50", nullable = true)
        BigDecimal avgHoursToDecision,
        @Schema(description = "Sum of the approved amounts of the claims the officer approved in the period, in the "
                + "currency of the officer's market.", example = "117000.00") BigDecimal totalApprovedAmount,
        @Schema(description = "Currency of totalApprovedAmount.", example = "INR") String currency) {
}
