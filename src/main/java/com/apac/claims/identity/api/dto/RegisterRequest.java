package com.apac.claims.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-registration. There is deliberately no role field: whoever registers becomes a claimant, and a {@code role}
 * property in the JSON is ignored, so registration can never be used to obtain staff rights.
 */
@Schema(description = "Self-registration of a claimant.")
public record RegisterRequest(
        @Schema(description = "Chosen user ID: 4 to 30 letters, digits, dots, dashes or underscores.",
                example = "new.claimant", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{4,30}$",
                message = "must be 4 to 30 letters, digits, dots, dashes or underscores") String userId,
        @Schema(description = "Password: 8 to 64 characters with at least one letter and one digit.",
                example = "Claimant@123", format = "password", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "must be 8 to 64 characters with at least one letter and one digit") String password,
        @Schema(description = "Full name.", example = "New Claimant", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 2, max = 100) String fullName,
        @Schema(description = "Email address.", example = "new.claimant@claims-demo.example",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email @Size(max = 150) String email,
        @Schema(description = "Code of the market the claimant lives in: IN, SG, AU, JP, MY or TH.", example = "IN",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter market code") String marketCode) {
}
