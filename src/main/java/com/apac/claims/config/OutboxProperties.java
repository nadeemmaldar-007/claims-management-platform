package com.apac.claims.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code app.outbox}.
 *
 * @param relay behaviour of the scheduled relay that delivers outbox events to handlers
 */
@Validated
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(@NotNull @Valid Relay relay) {

    /**
     * Settings under {@code app.outbox.relay}.
     *
     * @param enabled switches the scheduler on or off (tests call the relay directly instead)
     * @param pollIntervalMs delay between two polls
     * @param batchSize maximum number of events handled per poll
     * @param maxAttempts delivery attempts before an event is marked FAILED
     */
    public record Relay(boolean enabled, @Min(1) long pollIntervalMs, @Min(1) int batchSize, @Min(1) int maxAttempts) {
    }
}
