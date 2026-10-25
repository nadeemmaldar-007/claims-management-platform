package com.apac.claims.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code app.seed}.
 *
 * @param demoData whether demo users and policies are created at startup
 * @param sampleClaims whether sample claims are created when no claims exist
 */
@Validated
@ConfigurationProperties(prefix = "app.seed")
public record SeedProperties(boolean demoData, boolean sampleClaims) {
}
