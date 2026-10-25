package com.apac.claims.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.apac.claims.claim.api.dto.ClaimSummaryResponse;
import com.apac.claims.claim.api.dto.InformationRequestResponse;
import com.apac.claims.claim.api.dto.TimelineEntryResponse;
import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimAssessmentRepository;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimTimelineEntry;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.InformationRequest;
import com.apac.claims.claim.domain.InformationRequestRepository;
import com.apac.claims.claim.domain.InformationRequestStatus;
import com.apac.claims.claim.domain.TimelineEntryType;
import com.apac.claims.claim.domain.TimelineVisibility;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.market.domain.Market;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * What the mapper hides from a claimant and what it computes, without a database: staff user ids, the reason of an
 * escalation, the overdue flag and the decimal places of the currency.
 */
class ClaimMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    private final ClaimMapper mapper = new ClaimMapper(mock(ClaimAssessmentRepository.class),
            mock(InformationRequestRepository.class));

    private Market india;
    private Market japan;
    private AppUser claimant;
    private AppUser officer;
    private AppUser manager;

    @BeforeEach
    void setUp() {
        india = new Market("IN", "India", "INR", new BigDecimal("500000.00"), true);
        japan = new Market("JP", "Japan", "JPY", new BigDecimal("1000000"), true);
        claimant = new AppUser("claimant.in", "hash", "Claimant", "c@test.example", Role.CLAIMANT, india);
        officer = new AppUser("officer.in", "hash", "Officer", "o@test.example", Role.OFFICER, india);
        manager = new AppUser("manager.apac", "hash", "Manager", "m@test.example", Role.MANAGER, null);
    }

    private Claim claim(Market market, String amount) {
        Claim claim = new Claim("CLM-10001", ClaimType.MOTOR, ClaimStatus.UNDER_REVIEW, "MOT-IN-0001",
                new BigDecimal("1500000.00"), new BigDecimal("5000.00"), claimant, market, market.getCurrencyCode(),
                new BigDecimal(amount), LocalDate.of(2026, 10, 1), "MG Road", "Rear-ended at a traffic signal");
        claim.setAssignedOfficer(officer);
        return claim;
    }

    private ClaimTimelineEntry entry(TimelineEntryType type, String actor, String message) {
        return new ClaimTimelineEntry(claim(india, "45000.00"), type, null, ClaimStatus.UNDER_REVIEW, actor, message,
                TimelineVisibility.PUBLIC);
    }

    // ---- timeline -------------------------------------------------------------------------------------------------

    @Test
    void aClaimantSeesClaimsTeamForStaffAndTheirOwnUserIdForTheirOwnActions() {
        TimelineEntryResponse staffAction = mapper.toTimelineEntry(entry(TimelineEntryType.ASSIGNED, "officer.in",
                "Claim picked up."), claimant);
        TimelineEntryResponse ownAction = mapper.toTimelineEntry(entry(TimelineEntryType.SUBMITTED, "claimant.in",
                "Claim submitted."), claimant);

        assertThat(staffAction.actor()).isEqualTo("Claims team");
        assertThat(ownAction.actor()).isEqualTo("claimant.in");
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OFFICER", "MANAGER"})
    void staffSeeEveryActorAndTheFullEscalationRemarks(Role role) {
        AppUser staff = role == Role.OFFICER ? officer : manager;

        TimelineEntryResponse assigned = mapper.toTimelineEntry(entry(TimelineEntryType.ASSIGNED, "officer.in", "x"),
                staff);
        TimelineEntryResponse escalated = mapper.toTimelineEntry(entry(TimelineEntryType.ESCALATED, "officer.in",
                "Claim escalated: Suspected inflated estimate."), staff);

        assertThat(assigned.actor()).isEqualTo("officer.in");
        assertThat(escalated.message()).isEqualTo("Claim escalated: Suspected inflated estimate.");
    }

    @Test
    void aClaimantReadsANeutralTextOnAnEscalationWhateverTheOfficerWrote() {
        TimelineEntryResponse escalated = mapper.toTimelineEntry(entry(TimelineEntryType.ESCALATED, "officer.in",
                "Claim escalated: Suspected inflated estimate."), claimant);

        assertThat(escalated.message()).isEqualTo(ClaimMapper.ESCALATED_FOR_CLAIMANT)
                .doesNotContain("inflated");
    }

    @Test
    void otherEntriesKeepTheirMessageForTheClaimant() {
        TimelineEntryResponse rejected = mapper.toTimelineEntry(entry(TimelineEntryType.REJECTED, "officer.in",
                "Claim rejected. Reason: NOT_COVERED. Flood is not covered."), claimant);

        assertThat(rejected.message()).isEqualTo("Claim rejected. Reason: NOT_COVERED. Flood is not covered.");
    }

    // ---- claims ---------------------------------------------------------------------------------------------------

    @Test
    void aClaimantSummaryHasNoAssignedOfficerAndAStaffSummaryHasOne() {
        ClaimSummaryResponse forClaimant = mapper.toSummary(claim(india, "45000.00"), claimant);
        ClaimSummaryResponse forStaff = mapper.toSummary(claim(india, "45000.00"), officer);

        assertThat(forClaimant.assignedOfficerUserId()).isNull();
        assertThat(forStaff.assignedOfficerUserId()).isEqualTo("officer.in");
    }

    @Test
    void amountsHaveTheDecimalPlacesOfTheCurrency() {
        ClaimSummaryResponse yen = mapper.toSummary(claim(japan, "45000"), manager);
        ClaimSummaryResponse rupees = mapper.toSummary(claim(india, "45000"), manager);

        assertThat(yen.claimedAmount().scale()).isZero();
        assertThat(rupees.claimedAmount().scale()).isEqualTo(2);
        assertThat(yen.approvedAmount()).isNull();
    }

    @Test
    void theQueueItemCarriesTheAgeAndTheBreachFlagItWasGiven() {
        var item = mapper.toQueueItem(claim(india, "45000.00"), 73, true);

        assertThat(item.ageHours()).isEqualTo(73);
        assertThat(item.slaBreached()).isTrue();
        assertThat(item.claimNumber()).isEqualTo("CLM-10001");
    }

    // ---- information requests ---------------------------------------------------------------------------------------------

    private InformationRequest request(Instant dueAt, InformationRequestStatus status) {
        InformationRequest request = new InformationRequest(claim(india, "45000.00"), officer,
                "Please send the repair estimate.", dueAt);
        request.setStatus(status);
        return request;
    }

    @Test
    void anOpenRequestPastItsDueTimeIsOverdueAndOneBeforeItIsNot() {
        InformationRequestResponse late = mapper.toInformationRequest(
                request(NOW.minusSeconds(1), InformationRequestStatus.OPEN), officer, NOW);
        InformationRequestResponse onTime = mapper.toInformationRequest(
                request(NOW.plusSeconds(1), InformationRequestStatus.OPEN), officer, NOW);
        InformationRequestResponse exactly = mapper.toInformationRequest(
                request(NOW, InformationRequestStatus.OPEN), officer, NOW);

        assertThat(late.overdue()).isTrue();
        assertThat(onTime.overdue()).isFalse();
        assertThat(exactly.overdue()).as("due now is not yet late").isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = InformationRequestStatus.class, names = {"RESPONDED", "CANCELLED"})
    void aRequestThatIsNotOpenIsNeverOverdue(InformationRequestStatus status) {
        InformationRequestResponse response = mapper.toInformationRequest(request(NOW.minusSeconds(3600), status),
                officer, NOW);

        assertThat(response.overdue()).isFalse();
    }

    @Test
    void aClaimantSeesClaimsTeamAsTheAskerAndStaffSeeTheUserId() {
        InformationRequest request = request(NOW.plusSeconds(60), InformationRequestStatus.OPEN);

        assertThat(mapper.toInformationRequest(request, claimant, NOW).requestedBy()).isEqualTo("Claims team");
        assertThat(mapper.toInformationRequest(request, officer, NOW).requestedBy()).isEqualTo("officer.in");
    }

    @Test
    void theAnswerAndWhoGaveItAreShownOnceTheRequestIsAnswered() {
        InformationRequest request = request(NOW.plusSeconds(60), InformationRequestStatus.RESPONDED);
        request.setResponseText("The estimate is 42000 INR.");
        request.setRespondedBy(claimant);
        request.setRespondedAt(NOW);

        InformationRequestResponse response = mapper.toInformationRequest(request, officer, NOW);

        assertThat(response.responseText()).isEqualTo("The estimate is 42000 INR.");
        assertThat(response.respondedBy()).isEqualTo("claimant.in");
        assertThat(response.respondedAt()).isEqualTo(NOW);
    }
}
