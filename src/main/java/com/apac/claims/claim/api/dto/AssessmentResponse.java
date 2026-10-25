package com.apac.claims.claim.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.apac.claims.claim.domain.AssessmentRecommendation;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/** An internal assessment. Only staff ever receive it; claimants never do. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "An assessment of a claim (staff only).")
public record AssessmentResponse(
        @Schema(description = "Id of the assessment.", example = "17") Long id,
        @Schema(description = "User id of the assessing officer or manager.", example = "officer.in")
        String assessedBy,
        @Schema(description = "Loss as assessed.", example = "44000.00") BigDecimal assessedLossAmount,
        @Schema(description = "Deductible taken from the claim's policy snapshot.", example = "5000.00")
        BigDecimal deductibleApplied,
        @Schema(description = "Payout computed by the system: max(0, min(loss, sum insured) - deductible) when "
                + "coverage is confirmed, else 0.", example = "39000.00") BigDecimal recommendedPayout,
        @Schema(description = "Whether the policy covers the loss.", example = "true") boolean coverageConfirmed,
        @Schema(description = "APPROVE, REJECT or ESCALATE.", example = "APPROVE")
        AssessmentRecommendation recommendation,
        @Schema(description = "Internal notes.", example = "Photos match the repair estimate.") String notes,
        @Schema(description = "When the assessment was recorded (UTC).", example = "2026-10-25T10:15:30Z")
        Instant createdAt) {
}
