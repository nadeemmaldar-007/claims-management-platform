package com.apac.claims.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.apac.claims.shared.exception.ValidationFailedException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyRulesTest {

    @ParameterizedTest
    @CsvSource({"INR,2", "SGD,2", "AUD,2", "JPY,0", "MYR,2", "THB,2"})
    void scaleFollowsTheCurrency(String currencyCode, int expectedScale) {
        assertThat(MoneyRules.scaleFor(currencyCode)).isEqualTo(expectedScale);
    }

    @Test
    void unknownCurrencyCodeIsRejectedWithAClearMessage() {
        assertThatThrownBy(() -> MoneyRules.scaleFor("XYZ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown currency code: XYZ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankCurrencyCodeIsRejected(String currencyCode) {
        assertThatThrownBy(() -> MoneyRules.scaleFor(currencyCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void nullCurrencyCodeIsRejected() {
        assertThatThrownBy(() -> MoneyRules.scaleFor(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pseudoCurrencyWithoutDecimalPlacesIsRejected() {
        // XXX ("no currency") reports -1 fraction digits and cannot hold an amount.
        assertThatThrownBy(() -> MoneyRules.scaleFor("XXX"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no decimal places defined");
    }

    @Test
    void yenRejectsDecimals() {
        assertThatThrownBy(() -> MoneyRules.requireValidScale(new BigDecimal("10.5"), "JPY", "claimedAmount"))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    assertThat(e.getErrors()).hasSize(1);
                    assertThat(e.getErrors().get(0).field()).isEqualTo("claimedAmount");
                    assertThat(e.getErrors().get(0).message()).isEqualTo("must have no decimal places for JPY");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "10.00", "10.000", "0", "0.000", "1000000"})
    void yenAcceptsWholeAmountsEvenWithTrailingZeros(String amount) {
        assertThatCode(() -> MoneyRules.requireValidScale(new BigDecimal(amount), "JPY", "amount"))
                .doesNotThrowAnyException();
    }

    @Test
    void rupeeRejectsThreeDecimals() {
        assertThatThrownBy(() -> MoneyRules.requireValidScale(new BigDecimal("10.123"), "INR", "approvedAmount"))
                .isInstanceOfSatisfying(ValidationFailedException.class, e ->
                        assertThat(e.getErrors().get(0).message()).isEqualTo("must have at most 2 decimal places for INR"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "10.5", "10.50", "10.500", "0.01"})
    void rupeeAcceptsUpToTwoSignificantDecimals(String amount) {
        assertThatCode(() -> MoneyRules.requireValidScale(new BigDecimal(amount), "INR", "amount"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            "SGD, 10.005, 10.01",
            "SGD, 10.004, 10.00",
            "SGD, -10.005, -10.01",
            "SGD, 7, 7.00",
            "JPY, 10.5, 11",
            "JPY, 10.4, 10",
            "INR, 99.999, 100.00"
    })
    void normalizeRoundsHalfUpToTheCurrencyScale(String currency, String amount, String expected) {
        BigDecimal result = MoneyRules.normalize(new BigDecimal(amount), currency);

        // equals (not compareTo) on purpose: the scale itself is part of the contract.
        assertThat(result).isEqualTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @CsvSource({
            "INR, 500000.00, 1, 5000.00",
            "SGD, 10000.00, 1, 100.00",
            "JPY, 1000000.00, 1, 10000",
            "MYR, 25000.00, 1, 250.00",
            "SGD, 12345.67, 1, 123.46",
            "SGD, 1999.99, 2.5, 50.00",
            "JPY, 1001, 0.5, 5"
    })
    void percentOfRoundsHalfUpToTheCurrencyScale(String currency, String base, String percent, String expected) {
        BigDecimal result = MoneyRules.percentOf(new BigDecimal(base), new BigDecimal(percent), currency);

        assertThat(result).isEqualTo(new BigDecimal(expected));
    }

    @Test
    void nullArgumentsAreProgrammingErrors() {
        assertThatThrownBy(() -> MoneyRules.normalize(null, "INR")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MoneyRules.requireValidScale(null, "INR", "x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MoneyRules.percentOf(null, BigDecimal.ONE, "INR"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MoneyRules.percentOf(BigDecimal.TEN, null, "INR"))
                .isInstanceOf(NullPointerException.class);
    }
}
