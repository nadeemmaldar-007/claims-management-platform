package com.apac.claims.claim.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The officer's reason for passing a claim to a manager. Staff see it in the timeline; the claimant sees a neutral text. */
@Schema(description = "Why the claim is escalated.")
public record EscalateRequest(
        @Schema(description = "Reason for escalating, 10 to 1000 characters.",
                example = "Assessed payout is above my approval limit.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 10, max = 1000) String remarks) {
}
