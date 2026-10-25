package com.apac.claims.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code app.sla}. Targets are calendar hours, not business hours.
 *
 * @param firstReviewHours a submitted claim not picked up within this time is an SLA breach
 * @param decisionHours an open assigned claim older than this is an SLA breach
 * @param informationRequestDefaultDays due period used when a request does not state one (the API allows 1 to 30)
 */
@Validated
@ConfigurationProperties(prefix = "app.sla")
public record SlaProperties(
        @Min(1) int firstReviewHours,
        @Min(1) int decisionHours,
        @Min(1) @Max(30) int informationRequestDefaultDays) {
}
