package com.apac.claims.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.apac.claims.config.ReportingProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The conversion into the base currency: one multiplication, rounded once, half up, to two decimals. */
class CurrencyConverterTest {

    private final CurrencyConverter converter = ReportData.converter();

    @ParameterizedTest
    @CsvSource({
            "INR, 45000.00, 540.00",
            "SGD, 18000.00, 13320.00",
            "AUD, 6500.00, 4225.00",
            "JPY, 5000, 33.50",
            "MYR, 100.00, 21.00",
            "THB, 1000.00, 29.00"})
    void anAmountIsMultipliedByTheRateOfItsCurrency(String currency, String amount, String expected) {
        assertThat(converter.toBase(new BigDecimal(amount), currency)).isEqualByComparingTo(expected);
    }

    @Test
    void theResultAlwaysHasTwoDecimalPlaces() {
        assertThat(converter.toBase(new BigDecimal("5000"), "JPY").scale()).isEqualTo(2);
        assertThat(converter.toBase(new BigDecimal("1"), "INR").toPlainString()).isEqualTo("0.01");
    }

    @Test
    void roundingIsHalfUpAndNotHalfEven() {
        CurrencyConverter half = new CurrencyConverter(new ReportingProperties("USD", "STATIC_ILLUSTRATIVE",
                Map.of("INR", new BigDecimal("0.5"))));

        // 0.05 x 0.5 = 0.025: half up gives 0.03, half even would give 0.02.
        assertThat(half.toBase(new BigDecimal("0.05"), "INR")).isEqualByComparingTo("0.03");
        assertThat(half.toBase(new BigDecimal("0.15"), "INR")).isEqualByComparingTo("0.08");
    }

    @Test
    void theBaseCurrencyNeedsNoRateAndIsOnlyGivenTheRightScale() {
        assertThat(converter.toBase(new BigDecimal("12.5"), "USD")).isEqualTo(new BigDecimal("12.50"));
    }

    @Test
    void aCurrencyWithoutARateIsAnErrorAndNotAGuess() {
        assertThatThrownBy(() -> converter.toBase(new BigDecimal("1.00"), "CHF"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CHF");
    }

    @Test
    void localAmountsKeepTheDecimalPlacesOfTheirOwnCurrency() {
        assertThat(converter.local(new BigDecimal("5000.4"), "JPY").toPlainString()).isEqualTo("5000");
        assertThat(converter.local(new BigDecimal("5000"), "INR").toPlainString()).isEqualTo("5000.00");
    }

    @Test
    void zeroInTheBaseCurrencyHasTwoDecimalPlaces() {
        assertThat(converter.zeroBase().toPlainString()).isEqualTo("0.00");
    }

    @Test
    void theBaseCurrencyAndTheRateSourceComeFromTheConfiguration() {
        assertThat(converter.baseCurrency()).isEqualTo("USD");
        assertThat(converter.rateSource()).isEqualTo("STATIC_ILLUSTRATIVE");
    }

    @Test
    void theRateCheckNamesTheCurrencyThatHasNoRate() {
        assertThatCode(() -> converter.requireRatesFor(List.of("INR", "SGD", "AUD", "JPY", "MYR", "THB", "USD")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> converter.requireRatesFor(List.of("INR", "KRW")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KRW").hasMessageContaining("app.reporting.rates");
    }
}
