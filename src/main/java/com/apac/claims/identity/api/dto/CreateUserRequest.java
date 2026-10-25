package com.apac.claims.identity.api.dto;

import com.apac.claims.identity.domain.Role;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Creation of a staff account by a manager. An officer needs a market; a manager must not have one. Claimants are
 * not created here, they register themselves.
 */
@Schema(description = "A staff account (officer or manager) created by a manager.")
public record CreateUserRequest(
        @Schema(description = "User ID: 4 to 30 letters, digits, dots, dashes or underscores.",
                example = "officer.new", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{4,30}$",
                message = "must be 4 to 30 letters, digits, dots, dashes or underscores") String userId,
        @Schema(description = "Password: 8 to 64 characters with at least one letter and one digit.",
                example = "Officer@123", format = "password", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "must be 8 to 64 characters with at least one letter and one digit") String password,
        @Schema(description = "Full name.", example = "Officer New", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 2, max = 100) String fullName,
        @Schema(description = "Email address.", example = "officer.new@claims-demo.example",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email @Size(max = 150) String email,
        @Schema(description = "Role of the new account.", example = "OFFICER", allowableValues = {"OFFICER", "MANAGER"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Role role,
        @Schema(description = "Market code: required for an OFFICER, not allowed for a MANAGER.", example = "IN",
                nullable = true)
        @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter market code") String marketCode) {
}
