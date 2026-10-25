package com.apac.claims.claim.api.dto;

import java.math.BigDecimal;

import com.apac.claims.claim.domain.AssessmentRecommendation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An officer's assessment. The deductible and the recommended payout are not part of the request: the system computes
 * them from the claim's own policy snapshot, so two officers assessing the same loss get the same payout.
 */
@Schema(description = "The facts of an assessment; the payout is computed by the system.")
public record RecordAssessmentRequest(
        @Schema(description = "The loss as assessed, in the currency of the claim, with its decimal places.",
                example = "44000.00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
        @DecimalMax(value = "100000000", message = "must not exceed 100000000") BigDecimal assessedLossAmount,
        @Schema(description = "Whether the policy covers the loss. Without coverage the payout is 0.", example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Boolean coverageConfirmed,
        @Schema(description = "APPROVE, REJECT or ESCALATE.", example = "APPROVE",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull AssessmentRecommendation recommendation,
        @Schema(description = "Internal notes, never shown to the claimant. At most 2000 characters.",
                example = "Photos match the repair estimate.", nullable = true)
        @Size(max = 2000) String notes) {
}
