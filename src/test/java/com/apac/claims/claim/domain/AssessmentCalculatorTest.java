package com.apac.claims.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AssessmentCalculatorTest {

    private static BigDecimal payout(boolean coverage, String loss, String sumInsured, String deductible,
                                     String currency) {
        return AssessmentCalculator.recommendedPayout(coverage, new BigDecimal(loss), new BigDecimal(sumInsured),
                new BigDecimal(deductible), currency);
    }

    @ParameterizedTest
    @CsvSource({"INR, 0.00", "SGD, 0.00", "JPY, 0"})
    void withoutConfirmedCoverageThePayoutIsZeroInTheCurrencyScale(String currency, String expected) {
        BigDecimal result = payout(false, "50000.00", "1500000.00", "5000.00", currency);

        // equals on purpose: a zero with the wrong number of decimals would be shown wrongly.
        assertThat(result).isEqualTo(new BigDecimal(expected));
    }

    @Test
    void aLossBelowTheDeductibleGivesZero() {
        assertThat(payout(true, "4000.00", "1500000.00", "5000.00", "INR")).isEqualByComparingTo("0");
    }

    @Test
    void aLossEqualToTheDeductibleGivesZero() {
        assertThat(payout(true, "5000.00", "1500000.00", "5000.00", "INR")).isEqualByComparingTo("0");
    }

    @Test
    void aLossAboveTheSumInsuredIsCappedBeforeTheDeductible() {
        // min(40000, 30000) - 100 = 29900
        assertThat(payout(true, "40000.00", "30000.00", "100.00", "SGD")).isEqualByComparingTo("29900.00");
    }

    @ParameterizedTest
    @CsvSource({
            "INR, 245000.00, 5000000.00, 5000.00, 240000.00",
            "INR, 45000.00, 1500000.00, 5000.00, 40000.00",
            "SGD, 17500.00, 30000.00, 100.00, 17400.00",
            "SGD, 25000.00, 30000.00, 100.00, 24900.00",
            "AUD, 6500.00, 100000.00, 100.00, 6400.00",
            "MYR, 80000.00, 75000.00, 250.00, 74750.00"
    })
    void typicalExamplesFromTheSeededMarkets(String currency, String loss, String sumInsured, String deductible,
                                             String expected) {
        assertThat(payout(true, loss, sumInsured, deductible, currency)).isEqualByComparingTo(expected);
    }

    @Test
    void yenHasNoDecimalPlaces() {
        BigDecimal result = payout(true, "1000000.00", "3000000.00", "10000.00", "JPY");

        assertThat(result).isEqualTo(new BigDecimal("990000"));
        assertThat(result.scale()).isZero();
    }

    @Test
    void aFractionalYenAmountIsRoundedHalfUp() {
        assertThat(payout(true, "1000.50", "3000000.00", "0.00", "JPY")).isEqualTo(new BigDecimal("1001"));
    }

    @Test
    void theResultIsRoundedHalfUpToTwoDecimalsForDecimalCurrencies() {
        assertThat(payout(true, "100.005", "30000.00", "0.00", "SGD")).isEqualTo(new BigDecimal("100.01"));
        assertThat(payout(true, "100.004", "30000.00", "0.00", "SGD")).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void aZeroDeductibleDeductsNothing() {
        assertThat(payout(true, "1234.56", "30000.00", "0.00", "SGD")).isEqualByComparingTo("1234.56");
    }

    @Test
    void aLossExactlyAtTheSumInsuredPaysTheSumInsuredMinusTheDeductible() {
        assertThat(payout(true, "30000.00", "30000.00", "100.00", "SGD")).isEqualByComparingTo("29900.00");
    }

    @Test
    void numbersWithADifferentScaleAreComparedByValueNotByRepresentation() {
        // 2.0 and 2.00 are different for equals but the same amount: the payout must not depend on it.
        assertThat(payout(true, "1000.0", "30000", "100", "SGD")).isEqualByComparingTo("900.00");
        assertThat(payout(true, "1000.00", "30000.00", "100.00", "SGD")).isEqualByComparingTo("900.00");
    }

    @Test
    void anUnknownCurrencyIsAClearError() {
        assertThatThrownBy(() -> payout(true, "1.00", "10.00", "0.00", "XYZ"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown currency code");
    }

    @Test
    void missingAmountsAreProgrammingErrors() {
        assertThatThrownBy(() -> AssessmentCalculator.recommendedPayout(true, null, BigDecimal.TEN, BigDecimal.ONE, "INR"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AssessmentCalculator.recommendedPayout(true, BigDecimal.TEN, null, BigDecimal.ONE, "INR"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AssessmentCalculator.recommendedPayout(true, BigDecimal.TEN, BigDecimal.TEN, null, "INR"))
                .isInstanceOf(NullPointerException.class);
    }
}
