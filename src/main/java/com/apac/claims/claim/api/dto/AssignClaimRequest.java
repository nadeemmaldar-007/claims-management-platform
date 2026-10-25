package com.apac.claims.claim.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A manager's choice of officer for a claim: from the queue, or a reassignment of a claim under work. */
@Schema(description = "The officer to assign the claim to.")
public record AssignClaimRequest(
        @Schema(description = "User id of an active officer of the claim's market.", example = "officer.in",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 30) String officerUserId) {
}
