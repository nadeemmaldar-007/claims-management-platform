package com.apac.claims.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code app.security.jwt}. The secret must be long enough for HS256, so a weak value stops
 * the application at startup instead of producing weak tokens.
 *
 * @param secret shared signing secret, at least 32 characters (and therefore at least 32 bytes)
 * @param expiryMinutes lifetime of an access token
 * @param issuer value of the {@code iss} claim
 */
@Validated
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "must be at least 32 characters long (HS256 needs a 32-byte secret)")
        String secret,
        @Min(1) long expiryMinutes,
        @NotBlank String issuer) {
}
