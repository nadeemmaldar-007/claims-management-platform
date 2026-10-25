package com.apac.claims.config;

import java.math.BigDecimal;
import java.util.Map;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code app.reporting}. The rates are static and illustrative: they exist only to show totals
 * across markets in one base currency and must never be presented as real exchange rates.
 *
 * @param baseCurrency currency in which totals across markets are reported
 * @param rateSource label returned with every converted figure, {@code STATIC_ILLUSTRATIVE}
 * @param rates one unit of the keyed currency expressed in the base currency
 */
@Validated
@ConfigurationProperties(prefix = "app.reporting")
public record ReportingProperties(
        @NotBlank String baseCurrency,
        @NotBlank String rateSource,
        @NotEmpty Map<@NotBlank String, @DecimalMin(value = "0", inclusive = false) BigDecimal> rates) {
}
