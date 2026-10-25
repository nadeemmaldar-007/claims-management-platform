package com.apac.claims.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code app.integration.payment}.
 *
 * @param simulateFailure makes the simulated payment gateway fail, so the failure path can be demonstrated and tested
 */
@Validated
@ConfigurationProperties(prefix = "app.integration.payment")
public record PaymentProperties(boolean simulateFailure) {
}
