package com.apac.claims.claim.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Staff ask the claimant for more information; the claim waits in PENDING_INFORMATION until it is answered. */
@Schema(description = "A question for the claimant.")
public record CreateInformationRequest(
        @Schema(description = "What staff need to know, 10 to 1000 characters.",
                example = "Please send the repair estimate from the workshop.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 10, max = 1000) String question,
        @Schema(description = "Days the claimant has to answer, 1 to 30. Defaults to the configured number of days "
                + "(7).", example = "7", nullable = true)
        @Min(1) @Max(30) Integer dueInDays) {
}
