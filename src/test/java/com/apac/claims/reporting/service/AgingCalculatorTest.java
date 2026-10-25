package com.apac.claims.reporting.service;

import static com.apac.claims.reporting.service.ReportData.NOW;
import static com.apac.claims.reporting.service.ReportData.india;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.reporting.api.dto.AgingBucket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The aging bands with a fixed clock. The age is whole days since submission, so the boundaries between the bands are
 * 2 and 3, 7 and 8, 14 and 15 days, and 2 days 23 hours is still 2 days.
 */
class AgingCalculatorTest {

    private final AgingCalculator calculator = new AgingCalculator(ReportData.converter());
    private final ReportMarket india = new ReportMarket("IN", "INR");

    private static Duration ofHours(long hours) {
        return Duration.ofHours(hours);
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0", "1, 0", "2, 0", "3, 1", "5, 1", "7, 1", "8, 2", "10, 2", "14, 2", "15, 3", "16, 3", "400, 3"})
    void aClaimOfThatManyWholeDaysIsInThatBand(long days, int band) {
        assertThat(AgingCalculator.bandOf(days)).isEqualTo(band);
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0-2 days", "71, 0-2 days", "72, 3-7 days", "191, 3-7 days", "192, 8-14 days", "359, 8-14 days",
            "360, 15+ days"})
    void theBoundariesAreWholeDaysFromSubmission(long hours, String label) {
        List<AgingBucket> buckets = calculator.buckets(
                List.of(india(ClaimStatus.SUBMITTED, "100.00").age(ofHours(hours)).build()), NOW, india);

        assertThat(buckets.stream().filter(bucket -> bucket.count() == 1).map(AgingBucket::label))
                .containsExactly(label);
    }

    @Test
    void allFourBandsAreAlwaysGivenInOrderAlsoWhenNothingIsOpen() {
        List<AgingBucket> buckets = calculator.buckets(List.of(), NOW, null);

        assertThat(buckets).extracting(AgingBucket::label)
                .containsExactly("0-2 days", "3-7 days", "8-14 days", "15+ days");
        assertThat(buckets).extracting(AgingBucket::count).containsOnly(0L);
        assertThat(buckets).allSatisfy(bucket -> assertThat(bucket.outstandingExposureBase().toPlainString())
                .isEqualTo("0.00"));
    }

    @Test
    void onlyOpenClaimsAreCounted() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.SUBMITTED, "100.00").build(),
                india(ClaimStatus.UNDER_REVIEW, "100.00").build(),
                india(ClaimStatus.PENDING_INFORMATION, "100.00").build(),
                india(ClaimStatus.ESCALATED, "100.00").build(),
                india(ClaimStatus.APPROVED, "100.00").build(),
                india(ClaimStatus.REJECTED, "100.00").build(),
                india(ClaimStatus.SETTLED, "100.00").build());

        List<AgingBucket> buckets = calculator.buckets(claims, NOW, india);

        assertThat(buckets.get(0).count()).isEqualTo(4);
        assertThat(buckets.stream().mapToLong(AgingBucket::count).sum()).isEqualTo(4);
    }

    @Test
    void eachBandAddsUpTheClaimedAmountOfItsClaimsAndConvertsItMarketByMarket() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.SUBMITTED, "45000.00").age(ofHours(100)).build(),
                india(ClaimStatus.UNDER_REVIEW, "5000.00").age(ofHours(100)).build(),
                ReportData.claim("SG", "SGD", ClaimStatus.SUBMITTED, "1000.00").age(ofHours(100)).build(),
                india(ClaimStatus.SUBMITTED, "1000.00").age(ofHours(400)).build());

        List<AgingBucket> buckets = calculator.buckets(claims, NOW, null);

        AgingBucket threeToSeven = buckets.get(1);
        assertThat(threeToSeven.count()).isEqualTo(3);
        // 50000 INR x 0.012 = 600.00 and 1000 SGD x 0.74 = 740.00, converted separately and then added.
        assertThat(threeToSeven.outstandingExposureBase()).isEqualByComparingTo("1340.00");
        assertThat(buckets.get(3).outstandingExposureBase()).isEqualByComparingTo("12.00");
    }

    @Test
    void theLocalAmountIsOnlyGivenWhenTheReportCoversOneMarket() {
        List<ClaimFigures> claims = List.of(india(ClaimStatus.SUBMITTED, "45000.00").age(ofHours(100)).build());

        AgingBucket one = calculator.buckets(claims, NOW, india).get(1);
        AgingBucket several = calculator.buckets(claims, NOW, null).get(1);

        assertThat(one.outstandingExposureLocal()).isEqualByComparingTo("45000.00");
        assertThat(one.currencyCode()).isEqualTo("INR");
        assertThat(several.outstandingExposureLocal()).isNull();
        assertThat(several.currencyCode()).isNull();
    }
}
