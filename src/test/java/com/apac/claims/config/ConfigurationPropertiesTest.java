package com.apac.claims.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the real {@code application.yml} into the typed records, and proves that validation stops the application
 * on bad values. No profile is active, so these are the defaults a developer gets without any environment variable.
 */
class ConfigurationPropertiesTest {

    @Configuration
    @EnableConfigurationProperties({JwtProperties.class, SeedProperties.class, SlaProperties.class,
            ReportingProperties.class, OutboxProperties.class, PaymentProperties.class,
            NotificationProperties.class})
    static class PropertiesConfiguration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void jwtSettingsBind() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            JwtProperties jwt = context.getBean(JwtProperties.class);
            assertThat(jwt.issuer()).isEqualTo("claims-management-platform");
            assertThat(jwt.expiryMinutes()).isEqualTo(120);
            assertThat(jwt.secret()).hasSizeGreaterThanOrEqualTo(32);
        });
    }

    @Test
    void seedSettingsBind() {
        runner.run(context -> {
            SeedProperties seed = context.getBean(SeedProperties.class);
            assertThat(seed.demoData()).isTrue();
            assertThat(seed.sampleClaims()).isTrue();
        });
    }

    @Test
    void slaSettingsBind() {
        runner.run(context -> {
            SlaProperties sla = context.getBean(SlaProperties.class);
            assertThat(sla.firstReviewHours()).isEqualTo(48);
            assertThat(sla.decisionHours()).isEqualTo(240);
            assertThat(sla.informationRequestDefaultDays()).isEqualTo(7);
        });
    }

    @Test
    void reportingSettingsBindWithAllSixIllustrativeRates() {
        runner.run(context -> {
            ReportingProperties reporting = context.getBean(ReportingProperties.class);
            assertThat(reporting.baseCurrency()).isEqualTo("USD");
            assertThat(reporting.rateSource()).isEqualTo("STATIC_ILLUSTRATIVE");
            assertThat(reporting.rates()).containsOnlyKeys("INR", "SGD", "AUD", "JPY", "MYR", "THB");
            assertThat(reporting.rates().get("INR")).isEqualByComparingTo(new BigDecimal("0.012"));
            assertThat(reporting.rates().get("JPY")).isEqualByComparingTo(new BigDecimal("0.0067"));
            assertThat(reporting.rates().get("SGD")).isEqualByComparingTo(new BigDecimal("0.74"));
        });
    }

    @Test
    void outboxRelaySettingsBind() {
        runner.run(context -> {
            OutboxProperties.Relay relay = context.getBean(OutboxProperties.class).relay();
            assertThat(relay.enabled()).isTrue();
            assertThat(relay.pollIntervalMs()).isEqualTo(1000);
            assertThat(relay.batchSize()).isEqualTo(50);
            assertThat(relay.maxAttempts()).isEqualTo(5);
        });
    }

    @Test
    void paymentAndNotificationSettingsBind() {
        runner.run(context -> {
            assertThat(context.getBean(PaymentProperties.class).simulateFailure()).isFalse();
            assertThat(context.getBean(NotificationProperties.class).email().enabled()).isTrue();
        });
    }

    @Test
    void aTooShortJwtSecretStopsStartup() {
        runner.withPropertyValues("app.security.jwt.secret=too-short").run(context -> {
            assertThat(context).hasFailed();
            Throwable root = rootCauseOf(context.getStartupFailure());
            assertThat(root).isInstanceOf(BindValidationException.class);
            BindValidationException validation = (BindValidationException) root;
            assertThat(validation.getValidationErrors().getAllErrors())
                    .anySatisfy(error -> assertThat(error.toString()).contains("secret"));
        });
    }

    @Test
    void aSlaTargetOfZeroHoursStopsStartup() {
        runner.withPropertyValues("app.sla.first-review-hours=0").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aDefaultInformationRequestPeriodAboveThirtyDaysStopsStartup() {
        runner.withPropertyValues("app.sla.information-request-default-days=31")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aNonPositiveExchangeRateStopsStartup() {
        runner.withPropertyValues("app.reporting.rates.INR=0").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anOutboxBatchSizeOfZeroStopsStartup() {
        runner.withPropertyValues("app.outbox.relay.batch-size=0").run(context -> assertThat(context).hasFailed());
    }

    private static Throwable rootCauseOf(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
