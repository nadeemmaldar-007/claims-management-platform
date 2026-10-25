package com.apac.claims.reporting.service;

import static com.apac.claims.reporting.service.ReportData.NOW;
import static com.apac.claims.reporting.service.ReportData.claim;
import static com.apac.claims.reporting.service.ReportData.india;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.reporting.api.dto.MarketQueue;
import com.apac.claims.reporting.api.dto.OfficerWorkload;

import org.junit.jupiter.api.Test;

/**
 * The workload figures with a fixed clock and the targets of the application: a queued claim is late after more than 48
 * hours, an open claim that an officer has after more than 240 hours, both counted from submission.
 */
class WorkloadCalculatorTest {

    private final WorkloadCalculator calculator = new WorkloadCalculator(ReportData.sla());
    private final ReportOfficer anna = new ReportOfficer("officer.anna", "Anna", "IN", "INR");
    private final ReportOfficer ben = new ReportOfficer("officer.ben", "Ben", "IN", "INR");
    private final ReportMarket in = new ReportMarket("IN", "INR");

    private static Duration hours(long hours) {
        return Duration.ofHours(hours);
    }

    private OfficerWorkload workloadOf(ReportOfficer officer, List<ClaimFigures> claims) {
        return calculator.officers(List.of(officer), claims, NOW).get(0);
    }

    @Test
    void everyOfficerGivenHasARowAlsoOneWhoHasNoClaim() {
        List<OfficerWorkload> rows = calculator.officers(List.of(anna, ben), List.of(), NOW);

        assertThat(rows).extracting(OfficerWorkload::userId).containsExactly("officer.anna", "officer.ben");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.openAssigned()).isZero();
            assertThat(row.approvedAwaitingSettlement()).isZero();
            assertThat(row.slaBreaches()).isZero();
            assertThat(row.oldestOpenAgeHours()).isNull();
        });
    }

    @Test
    void theOpenClaimsAreCountedByStatusAndOnlyForTheOfficerWhoHasThem() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.UNDER_REVIEW, "100.00").officer("officer.anna").build(),
                india(ClaimStatus.UNDER_REVIEW, "100.00").officer("officer.anna").build(),
                india(ClaimStatus.PENDING_INFORMATION, "100.00").officer("officer.anna").build(),
                india(ClaimStatus.ESCALATED, "100.00").officer("officer.anna").build(),
                india(ClaimStatus.UNDER_REVIEW, "100.00").officer("officer.ben").build());

        OfficerWorkload row = workloadOf(anna, claims);

        assertThat(row.underReview()).isEqualTo(2);
        assertThat(row.pendingInformation()).isEqualTo(1);
        assertThat(row.escalated()).isEqualTo(1);
        assertThat(row.openAssigned()).isEqualTo(4);
        assertThat(workloadOf(ben, claims).openAssigned()).isEqualTo(1);
    }

    @Test
    void anApprovedClaimWaitsForSettlementAndIsNotOpen() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.APPROVED, "100.00").officer("officer.anna").age(hours(1000)).build(),
                india(ClaimStatus.SETTLED, "100.00").officer("officer.anna").build(),
                india(ClaimStatus.REJECTED, "100.00").officer("officer.anna").build());

        OfficerWorkload row = workloadOf(anna, claims);

        assertThat(row.approvedAwaitingSettlement()).isEqualTo(1);
        assertThat(row.openAssigned()).isZero();
        assertThat(row.oldestOpenAgeHours()).isNull();
        assertThat(row.slaBreaches()).isZero();
    }

    @Test
    void theOldestOpenClaimGivesTheAgeInWholeHoursSinceSubmission() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.UNDER_REVIEW, "100.00").officer("officer.anna").age(hours(10)).build(),
                india(ClaimStatus.ESCALATED, "100.00").officer("officer.anna").age(hours(100).plusMinutes(59)).build(),
                india(ClaimStatus.PENDING_INFORMATION, "100.00").officer("officer.anna").age(hours(30)).build());

        assertThat(workloadOf(anna, claims).oldestOpenAgeHours()).isEqualTo(100);
    }

    @Test
    void anOpenClaimIsLateOnlyAfterMoreThanTheDecisionTarget() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.UNDER_REVIEW, "100.00").officer("officer.anna").age(hours(240)).build(),
                india(ClaimStatus.UNDER_REVIEW, "100.00").officer("officer.anna").age(hours(241)).build(),
                india(ClaimStatus.ESCALATED, "100.00").officer("officer.anna").age(hours(500)).build(),
                india(ClaimStatus.APPROVED, "100.00").officer("officer.anna").age(hours(900)).build());

        assertThat(workloadOf(anna, claims).slaBreaches()).isEqualTo(2);
    }

    @Test
    void theQueueOfAMarketHoldsTheSubmittedClaimsNobodyHasTaken() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.SUBMITTED, "100.00").build(),
                india(ClaimStatus.SUBMITTED, "100.00").build(),
                india(ClaimStatus.UNDER_REVIEW, "100.00").officer("officer.anna").build(),
                claim("SG", "SGD", ClaimStatus.SUBMITTED, "100.00").build());

        MarketQueue queue = calculator.queues(List.of(in), claims, NOW).get(0);

        assertThat(queue.marketCode()).isEqualTo("IN");
        assertThat(queue.unassignedInQueue()).isEqualTo(2);
    }

    @Test
    void aQueuedClaimIsLateOnlyAfterMoreThanTheFirstReviewTarget() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.SUBMITTED, "100.00").age(hours(48)).build(),
                india(ClaimStatus.SUBMITTED, "100.00").age(hours(49)).build(),
                india(ClaimStatus.SUBMITTED, "100.00").age(hours(1000)).build(),
                india(ClaimStatus.SUBMITTED, "100.00").age(hours(2)).build());

        MarketQueue queue = calculator.queues(List.of(in), claims, NOW).get(0);

        assertThat(queue.unassignedInQueue()).isEqualTo(4);
        assertThat(queue.queueSlaBreaches()).isEqualTo(2);
    }

    @Test
    void everyMarketGivenHasAQueueRowAlsoAnEmptyOne() {
        List<MarketQueue> queues = calculator.queues(List.of(in, new ReportMarket("SG", "SGD")), List.of(), NOW);

        assertThat(queues).extracting(MarketQueue::marketCode).containsExactly("IN", "SG");
        assertThat(queues).allSatisfy(queue -> assertThat(queue.unassignedInQueue()).isZero());
    }

    @Test
    void theOpenClaimBreachesOfTheOverviewCountEveryOfficersClaimsEvenOfAnOfficerWithoutARow() {
        List<ClaimFigures> claims = List.of(
                india(ClaimStatus.UNDER_REVIEW, "100.00").officer("officer.gone").age(hours(300)).build(),
                india(ClaimStatus.ESCALATED, "100.00").officer("officer.anna").age(hours(300)).build(),
                india(ClaimStatus.UNDER_REVIEW, "100.00").officer("officer.anna").age(hours(10)).build(),
                india(ClaimStatus.SUBMITTED, "100.00").age(hours(300)).build(),
                india(ClaimStatus.APPROVED, "100.00").officer("officer.anna").age(hours(300)).build());

        assertThat(calculator.openClaimSlaBreaches(claims, NOW)).isEqualTo(2);
    }
}
