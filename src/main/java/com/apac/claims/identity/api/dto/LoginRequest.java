package com.apac.claims.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credentials for {@code POST /api/v1/auth/login}. The role is a separate query parameter. The password is only
 * checked for presence here: enforcing the password rules at login would tell an attacker what they are.
 */
@Schema(description = "Credentials of the account that wants to log in.")
public record LoginRequest(
        @Schema(description = "User ID of the account. Not case sensitive.", example = "claimant.in",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 30) String userId,
        @Schema(description = "Password of the account.", example = "Claimant@123", format = "password",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 64) String password) {
}
