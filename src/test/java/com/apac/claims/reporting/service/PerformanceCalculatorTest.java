package com.apac.claims.reporting.service;

import static com.apac.claims.reporting.service.ReportData.NOW;
import static com.apac.claims.reporting.service.ReportData.claim;
import static com.apac.claims.reporting.service.ReportData.india;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.DecisionType;
import com.apac.claims.reporting.api.dto.OfficerPerformance;

import org.junit.jupiter.api.Test;

/**
 * The performance figures with hand-made claims and a fixed period: the 30 days that end at {@code NOW}. The period is
 * half open (from the first instant, up to but not including the last), which the boundary tests pin down.
 */
class PerformanceCalculatorTest {

    private static final Instant FROM = NOW.minus(Duration.ofDays(30));

    private final PerformanceCalculator calculator = new PerformanceCalculator(ReportData.converter());
    private final ReportOfficer anna = new ReportOfficer("officer.anna", "Anna", "IN", "INR");
    private final ReportOfficer ben = new ReportOfficer("officer.ben", "Ben", "IN", "INR");

    private static Duration hours(double hours) {
        return Duration.ofMillis(Math.round(hours * 3_600_000));
    }

    private OfficerPerformance of(ReportOfficer officer, List<ClaimFigures> claims) {
        return calculator.officers(List.of(officer), claims, FROM, NOW).get(0);
    }

    /** A claim of Anna's that she approved: submitted 10 days ago, decided 30 hours after submission. */
    private static ReportData.Figures approvedByAnna(String approvedAmount) {
        return india(ClaimStatus.APPROVED, "50000.00").age(Duration.ofDays(10)).approved(approvedAmount)
                .officer("officer.anna").decidedBy("officer.anna", DecisionType.APPROVED, hours(30));
    }

    private static ReportData.Figures rejectedByAnna() {
        return india(ClaimStatus.REJECTED, "50000.00").age(Duration.ofDays(10)).officer("officer.anna")
                .decidedBy("officer.anna", DecisionType.REJECTED, hours(10));
    }

    @Test
    void anOfficerWithNothingInThePeriodHasARowWithZerosAndNoAveragesAndNoRate() {
        OfficerPerformance row = of(anna, List.of());

        assertThat(row.decidedCount()).isZero();
        assertThat(row.approvedCount()).isZero();
        assertThat(row.rejectedCount()).isZero();
        assertThat(row.settledCount()).isZero();
        assertThat(row.approvalRate()).isNull();
        assertThat(row.avgHoursToFirstReview()).isNull();
        assertThat(row.avgHoursToDecision()).isNull();
        assertThat(row.totalApprovedAmount().toPlainString()).isEqualTo("0.00");
        assertThat(row.currency()).isEqualTo("INR");
        assertThat(row.userId()).isEqualTo("officer.anna");
        assertThat(row.marketCode()).isEqualTo("IN");
    }

    @Test
    void decisionsAreCountedByTheOutcomeAndTheApprovalRateIsApprovedOverDecided() {
        List<ClaimFigures> claims = List.of(approvedByAnna("39000.00").build(), approvedByAnna("1000.00").build(),
                rejectedByAnna().build());

        OfficerPerformance row = of(anna, claims);

        assertThat(row.decidedCount()).isEqualTo(3);
        assertThat(row.approvedCount()).isEqualTo(2);
        assertThat(row.rejectedCount()).isEqualTo(1);
        assertThat(row.approvalRate().toPlainString()).isEqualTo("0.6667");
    }

    @Test
    void theApprovalRateIsOneWhenEverythingWasApprovedAndZeroWhenNothingWas() {
        assertThat(of(anna, List.of(approvedByAnna("100.00").build())).approvalRate().toPlainString())
                .isEqualTo("1.0000");
        assertThat(of(anna, List.of(rejectedByAnna().build())).approvalRate().toPlainString()).isEqualTo("0.0000");
    }

    @Test
    void aClaimThatWasApprovedAndThenSettledStillCountsAsAnApproval() {
        ClaimFigures settled = approvedByAnna("39000.00").settledAt(NOW.minusSeconds(60)).build();

        OfficerPerformance row = of(anna, List.of(new ClaimFigures(settled.marketCode(), settled.currencyCode(),
                settled.claimType(), ClaimStatus.SETTLED, settled.createdAt(), settled.claimedAmount(),
                settled.approvedAmount(), settled.approvedAmount(), settled.officerUserId(), settled.assignedAt(),
                settled.decidedAt(), settled.decidedByUserId(), settled.decisionType(), settled.settledAt())));

        assertThat(row.approvedCount()).isEqualTo(1);
        assertThat(row.settledCount()).isEqualTo(1);
    }

    @Test
    void aDecisionOfAManagerIsCountedForNobodyAndNeverForTheOfficerWhoHadTheClaim() {
        ClaimFigures byManager = india(ClaimStatus.APPROVED, "50000.00").age(Duration.ofDays(10))
                .approved("45000.00").officer("officer.anna")
                .decidedBy("manager.apac", DecisionType.APPROVED, hours(30)).build();

        OfficerPerformance row = of(anna, List.of(byManager));

        assertThat(row.decidedCount()).isZero();
        assertThat(row.totalApprovedAmount().signum()).isZero();
    }

    @Test
    void anotherOfficersDecisionsAreNotCounted() {
        List<ClaimFigures> claims = List.of(approvedByAnna("39000.00").build());

        assertThat(of(ben, claims).decidedCount()).isZero();
    }

    @Test
    void theTotalApprovedAddsTheApprovedAmountsOfTheClaimsTheOfficerApproved() {
        List<ClaimFigures> claims = List.of(approvedByAnna("39000.00").build(), approvedByAnna("1000.50").build(),
                rejectedByAnna().build());

        assertThat(of(anna, claims).totalApprovedAmount()).isEqualByComparingTo("40000.50");
    }

    @Test
    void theTotalApprovedUsesTheDecimalPlacesOfTheOfficersCurrency() {
        ReportOfficer kenji = new ReportOfficer("officer.kenji", "Kenji", "JP", "JPY");
        ClaimFigures claim = claim("JP", "JPY", ClaimStatus.APPROVED, "50000").age(Duration.ofDays(5))
                .approved("40000").officer("officer.kenji")
                .decidedBy("officer.kenji", DecisionType.APPROVED, hours(5)).build();

        OfficerPerformance row = of(kenji, List.of(claim));

        assertThat(row.totalApprovedAmount().toPlainString()).isEqualTo("40000");
        assertThat(row.currency()).isEqualTo("JPY");
    }

    @Test
    void theAverageHoursToDecisionRunFromSubmissionToTheDecisionOfTheClaimsTheOfficerDecided() {
        List<ClaimFigures> claims = List.of(approvedByAnna("100.00").build(), rejectedByAnna().build());

        // 30 hours and 10 hours: the mean is 20.
        assertThat(of(anna, claims).avgHoursToDecision().toPlainString()).isEqualTo("20.00");
    }

    @Test
    void theAverageHoursToFirstReviewRunFromSubmissionToTheAssignmentOfTheClaimsAssignedInThePeriod() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.UNDER_REVIEW, "100.00").age(Duration.ofDays(5)).officer("officer.anna")
                        .assignedAfter(hours(2)).build(),
                india(ClaimStatus.UNDER_REVIEW, "100.00").age(Duration.ofDays(5)).officer("officer.anna")
                        .assignedAfter(hours(5.5)).build(),
                india(ClaimStatus.UNDER_REVIEW, "100.00").age(Duration.ofDays(5)).officer("officer.ben")
                        .assignedAfter(hours(40)).build());

        assertThat(of(anna, claims).avgHoursToFirstReview().toPlainString()).isEqualTo("3.75");
        assertThat(of(ben, claims).avgHoursToFirstReview().toPlainString()).isEqualTo("40.00");
    }

    @Test
    void theAveragesAreRoundedHalfUpToTwoDecimals() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.UNDER_REVIEW, "100.00").age(Duration.ofDays(5)).officer("officer.anna")
                        .assignedAfter(Duration.ofMinutes(20)).build(),
                india(ClaimStatus.UNDER_REVIEW, "100.00").age(Duration.ofDays(5)).officer("officer.anna")
                        .assignedAfter(Duration.ofMinutes(20)).build(),
                india(ClaimStatus.UNDER_REVIEW, "100.00").age(Duration.ofDays(5)).officer("officer.anna")
                        .assignedAfter(Duration.ofMinutes(21)).build());

        // (20 + 20 + 21) minutes over 3 claims = 20.3333 minutes = 0.33888 hours -> 0.34.
        assertThat(of(anna, claims).avgHoursToFirstReview().toPlainString()).isEqualTo("0.34");
    }

    @Test
    void aSettledClaimCountsForTheOfficerWhoHasItWhenItWasSettledInThePeriod() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.SETTLED, "100.00").officer("officer.anna").settledAt(NOW.minusSeconds(3600)).build(),
                india(ClaimStatus.SETTLED, "100.00").officer("officer.anna")
                        .settledAt(FROM.minusSeconds(3600)).build(),
                india(ClaimStatus.SETTLED, "100.00").officer("officer.ben").settledAt(NOW.minusSeconds(3600)).build());

        assertThat(of(anna, claims).settledCount()).isEqualTo(1);
        assertThat(of(ben, claims).settledCount()).isEqualTo(1);
    }

    @Test
    void thePeriodIncludesItsFirstInstantAndExcludesItsEnd() {
        Stream<Instant> times = Stream.of(FROM.minusMillis(1), FROM, NOW.minusMillis(1), NOW);

        List<ClaimFigures> claims = times.map(time -> india(ClaimStatus.APPROVED, "100.00").age(Duration.ofDays(40))
                .approved("100.00").officer("officer.anna")
                .decidedBy("officer.anna", DecisionType.APPROVED, Duration.ZERO).decidedAt(time).build()).toList();

        // Only FROM and NOW minus a millisecond are inside the half-open period.
        assertThat(of(anna, claims).decidedCount()).isEqualTo(2);
    }

    @Test
    void everyOfficerGivenHasARowInTheOrderGiven() {
        List<OfficerPerformance> rows = calculator.officers(List.of(ben, anna), List.of(), FROM, NOW);

        assertThat(rows).extracting(OfficerPerformance::userId).containsExactly("officer.ben", "officer.anna");
    }
}
