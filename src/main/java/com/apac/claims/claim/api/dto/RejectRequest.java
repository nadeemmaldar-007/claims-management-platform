package com.apac.claims.claim.api.dto;

import com.apac.claims.claim.domain.RejectionReason;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Rejection of a claim. The reason code and the remarks are the explanation the claimant is shown. */
@Schema(description = "Why the claim is rejected; shown to the claimant.")
public record RejectRequest(
        @Schema(description = "POLICY_INACTIVE, NOT_COVERED, FRAUD_SUSPECTED, INSUFFICIENT_EVIDENCE, DUPLICATE_CLAIM, "
                + "INFORMATION_NOT_PROVIDED or OTHER.", example = "NOT_COVERED",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull RejectionReason reasonCode,
        @Schema(description = "Explanation for the claimant, 10 to 1000 characters.",
                example = "Flood damage is not covered by this motor policy.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 10, max = 1000) String remarks) {
}
