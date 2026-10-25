package com.apac.claims.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The result of a successful login: the access token and who it belongs to. */
@Schema(description = "Result of a successful login.")
public record LoginResponse(
        @Schema(description = "The JWT access token. Paste only this value into the Authorize dialog of Swagger UI.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJjbGFpbWFudC5pbiJ9.signature")
        String accessToken,
        @Schema(description = "Always Bearer.", example = "Bearer") String tokenType,
        @Schema(description = "Seconds until the token expires.", example = "7200") long expiresInSeconds,
        UserSummary user) {
}
