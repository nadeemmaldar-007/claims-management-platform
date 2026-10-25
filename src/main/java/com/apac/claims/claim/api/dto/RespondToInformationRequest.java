package com.apac.claims.claim.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The claimant's answer to an information request. Text only: file uploads are out of scope. */
@Schema(description = "The claimant's answer.")
public record RespondToInformationRequest(
        @Schema(description = "The answer, 10 to 2000 characters. Text only.",
                example = "The repair estimate is 42000 INR from Sunrise Auto Works, Bengaluru.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 10, max = 2000) String responseText) {
}
