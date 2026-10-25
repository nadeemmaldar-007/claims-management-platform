package com.apac.claims.identity.api.dto;

import com.apac.claims.identity.domain.Role;

import io.swagger.v3.oas.annotations.media.Schema;

/** The short description of the account that is returned with a login. */
@Schema(description = "The logged-in account.")
public record UserSummary(
        @Schema(description = "User ID, always lower case.", example = "claimant.in") String userId,
        @Schema(description = "Display name.", example = "Claimant India") String fullName,
        @Schema(description = "Role of the account.", example = "CLAIMANT") Role role,
        @Schema(description = "Market code; absent for managers, who work across markets.", example = "IN",
                nullable = true) String marketCode) {
}
