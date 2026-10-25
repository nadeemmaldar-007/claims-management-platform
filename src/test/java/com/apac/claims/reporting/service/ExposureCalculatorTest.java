package com.apac.claims.reporting.service;

import static com.apac.claims.reporting.service.ReportData.claim;
import static com.apac.claims.reporting.service.ReportData.india;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.reporting.api.dto.ExposureRow;
import com.apac.claims.reporting.api.dto.ExposureTotal;
import com.apac.claims.reporting.api.dto.MarketOutstanding;

import org.junit.jupiter.api.Test;

/**
 * The exposure arithmetic with hand-made claims: what is open, what waits for payment, what is paid, the conversion into
 * the base currency, and that markets are never added in their local currencies.
 */
class ExposureCalculatorTest {

    private final ExposureCalculator calculator = new ExposureCalculator(ReportData.converter());
    private final ReportMarket in = new ReportMarket("IN", "INR");
    private final ReportMarket sg = new ReportMarket("SG", "SGD");
    private final ReportMarket jp = new ReportMarket("JP", "JPY");

    private static ExposureRow row(List<ExposureRow> rows, String market, ClaimType type) {
        return rows.stream().filter(row -> row.marketCode().equals(market) && row.claimType() == type)
                .findFirst().orElseThrow();
    }

    @Test
    void everyMarketHasARowForEveryClaimTypeAlsoWhenThereAreNoClaims() {
        List<ExposureRow> rows = calculator.rows(List.of(in, sg), List.of());

        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(ExposureRow::marketCode).containsExactly("IN", "IN", "SG", "SG");
        assertThat(rows).extracting(ExposureRow::claimType).containsExactly(ClaimType.MOTOR, ClaimType.PROPERTY,
                ClaimType.MOTOR, ClaimType.PROPERTY);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.outstandingExposure().signum()).isZero();
            assertThat(row.outstandingExposureBase().toPlainString()).isEqualTo("0.00");
        });
    }

    @Test
    void openClaimsCountWithTheirClaimedAmountApprovedOnesWithTheirApprovedAmountUntilTheyArePaid() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.SUBMITTED, "1000.00").build(),
                india(ClaimStatus.UNDER_REVIEW, "2000.00").build(),
                india(ClaimStatus.PENDING_INFORMATION, "3000.00").build(),
                india(ClaimStatus.ESCALATED, "4000.00").build(),
                india(ClaimStatus.APPROVED, "9000.00").approved("8000.00").build(),
                india(ClaimStatus.SETTLED, "7000.00").approved("6500.00").settled("6500.00").build(),
                india(ClaimStatus.REJECTED, "5000.00").build());

        ExposureRow motor = row(calculator.rows(List.of(in), claims), "IN", ClaimType.MOTOR);

        assertThat(motor.openClaimedAmount()).isEqualByComparingTo("10000.00");
        assertThat(motor.approvedAwaitingPayment()).isEqualByComparingTo("8000.00");
        assertThat(motor.settledTotal()).isEqualByComparingTo("6500.00");
        assertThat(motor.outstandingExposure()).isEqualByComparingTo("18000.00");
    }

    @Test
    void theTwoClaimTypesAreAddedSeparately() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.SUBMITTED, "1000.00").type(ClaimType.MOTOR).build(),
                india(ClaimStatus.SUBMITTED, "250.00").type(ClaimType.PROPERTY).build());

        List<ExposureRow> rows = calculator.rows(List.of(in), claims);

        assertThat(row(rows, "IN", ClaimType.MOTOR).openClaimedAmount()).isEqualByComparingTo("1000.00");
        assertThat(row(rows, "IN", ClaimType.PROPERTY).openClaimedAmount()).isEqualByComparingTo("250.00");
    }

    @Test
    void everyFigureIsConvertedToTheBaseCurrencyOnItsOwn() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.SUBMITTED, "45000.00").build(),
                india(ClaimStatus.APPROVED, "40000.00").approved("39000.00").build(),
                india(ClaimStatus.SETTLED, "10000.00").approved("10000.00").settled("10000.00").build());

        ExposureRow motor = row(calculator.rows(List.of(in), claims), "IN", ClaimType.MOTOR);

        assertThat(motor.openClaimedAmountBase()).isEqualByComparingTo("540.00");
        assertThat(motor.approvedAwaitingPaymentBase()).isEqualByComparingTo("468.00");
        assertThat(motor.settledTotalBase()).isEqualByComparingTo("120.00");
        assertThat(motor.outstandingExposureBase()).isEqualByComparingTo("1008.00");
    }

    @Test
    void theTotalIsTheSumOfTheConvertedRowsNeverOfTheLocalAmounts() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.SUBMITTED, "100000.00").build(),
                claim("SG", "SGD", ClaimStatus.SUBMITTED, "100.00").build(),
                claim("JP", "JPY", ClaimStatus.UNDER_REVIEW, "5000").build());

        List<ExposureRow> rows = calculator.rows(List.of(in, sg, jp), claims);
        ExposureTotal total = calculator.total(rows);

        // 100000 INR = 1200.00, 100 SGD = 74.00, 5000 JPY = 33.50 (not 105100 of anything).
        assertThat(total.openClaimedAmount()).isEqualByComparingTo("1307.50");
        assertThat(total.outstandingExposure()).isEqualByComparingTo("1307.50");
        assertThat(total.approvedAwaitingPayment().toPlainString()).isEqualTo("0.00");
        assertThat(total.settledTotal().toPlainString()).isEqualTo("0.00");
    }

    @Test
    void localAmountsOfAYenMarketHaveNoDecimalPlaces() {
        List<ClaimFigures> claims = List.of(claim("JP", "JPY", ClaimStatus.SUBMITTED, "5000").build());

        ExposureRow motor = row(calculator.rows(List.of(jp), claims), "JP", ClaimType.MOTOR);

        assertThat(motor.openClaimedAmount().toPlainString()).isEqualTo("5000");
        assertThat(motor.outstandingExposure().toPlainString()).isEqualTo("5000");
        assertThat(motor.outstandingExposureBase().toPlainString()).isEqualTo("33.50");
    }

    @Test
    void anApprovedClaimWithoutAnApprovedAmountCountsAsNothingAndDoesNotFail() {
        List<ClaimFigures> claims = List.of(india(ClaimStatus.APPROVED, "9000.00").build());

        ExposureRow motor = row(calculator.rows(List.of(in), claims), "IN", ClaimType.MOTOR);

        assertThat(motor.approvedAwaitingPayment().signum()).isZero();
    }

    @Test
    void claimsOfAMarketOutsideTheScopeAreIgnored() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.SUBMITTED, "1000.00").build(),
                claim("SG", "SGD", ClaimStatus.SUBMITTED, "9999.00").build());

        List<ExposureRow> rows = calculator.rows(List.of(in), claims);

        assertThat(rows).extracting(ExposureRow::marketCode).containsOnly("IN");
        assertThat(calculator.total(rows).openClaimedAmount()).isEqualByComparingTo("12.00");
    }

    @Test
    void theOutstandingExposureOfAMarketAddsItsRowsInBothCurrencies() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.SUBMITTED, "1000.00").type(ClaimType.MOTOR).build(),
                india(ClaimStatus.APPROVED, "3000.00").approved("2500.00").type(ClaimType.PROPERTY).build(),
                claim("SG", "SGD", ClaimStatus.SUBMITTED, "200.00").build());
        List<ExposureRow> rows = calculator.rows(List.of(in, sg), claims);

        List<MarketOutstanding> perMarket = calculator.outstandingByMarket(List.of(in, sg), rows);

        assertThat(perMarket).extracting(MarketOutstanding::marketCode).containsExactly("IN", "SG");
        assertThat(perMarket.get(0).outstandingExposure()).isEqualByComparingTo("3500.00");
        assertThat(perMarket.get(0).currencyCode()).isEqualTo("INR");
        assertThat(perMarket.get(0).outstandingExposureBase()).isEqualByComparingTo("42.00");
        assertThat(perMarket.get(1).outstandingExposure()).isEqualByComparingTo("200.00");
        assertThat(perMarket.get(1).outstandingExposureBase()).isEqualByComparingTo("148.00");
    }

    @Test
    void theBaseFiguresAreRoundedHalfUpOncePerFigure() {
        // 1.35 INR x 0.012 = 0.0162 -> 0.02; two such claims are 2.70 INR x 0.012 = 0.0324 -> 0.03, not 0.04.
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.SUBMITTED, "1.35").build(), india(ClaimStatus.SUBMITTED, "1.35").build());

        ExposureRow motor = row(calculator.rows(List.of(in), claims), "IN", ClaimType.MOTOR);

        assertThat(motor.openClaimedAmount()).isEqualByComparingTo(new BigDecimal("2.70"));
        assertThat(motor.openClaimedAmountBase()).isEqualByComparingTo("0.03");
    }
}
