package com.apac.claims.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code app.notification}.
 *
 * @param email settings of the email channel, which is simulated by a logging adapter
 */
@Validated
@ConfigurationProperties(prefix = "app.notification")
public record NotificationProperties(@NotNull @Valid Email email) {

    /**
     * Settings under {@code app.notification.email}.
     *
     * @param enabled whether the logging email channel writes its line
     */
    public record Email(boolean enabled) {
    }
}
