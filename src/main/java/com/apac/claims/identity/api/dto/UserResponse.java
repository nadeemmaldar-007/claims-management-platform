package com.apac.claims.identity.api.dto;

import java.time.Instant;

import com.apac.claims.identity.domain.Role;

import io.swagger.v3.oas.annotations.media.Schema;

/** An account as shown by the API. It never contains the password hash. */
@Schema(description = "A platform account.")
public record UserResponse(
        @Schema(description = "User ID, always lower case.", example = "officer.in") String userId,
        @Schema(description = "Display name.", example = "Officer India") String fullName,
        @Schema(description = "Email address, always lower case.", example = "officer.in@claims-demo.example")
        String email,
        @Schema(description = "Role of the account.", example = "OFFICER") Role role,
        @Schema(description = "Market code; absent for managers.", example = "IN", nullable = true) String marketCode,
        @Schema(description = "Whether the account can log in.", example = "true") boolean active,
        @Schema(description = "When the account was created (UTC).", example = "2026-10-25T10:15:30Z")
        Instant createdAt) {
}
