package com.apac.claims.claim.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.RejectionReason;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.market.domain.Market;
import com.apac.claims.shared.event.DomainEvent;
import com.apac.claims.shared.event.EventTypes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ClaimEventsTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final ClaimEvents events = new ClaimEvents(Clock.fixed(NOW, ZoneOffset.UTC));
    private Market india;
    private AppUser claimant;
    private AppUser officer;

    @BeforeEach
    void setUp() {
        india = new Market("IN", "India", "INR", new BigDecimal("500000.00"), true);
        claimant = new AppUser("claimant.in", "hash", "Claimant India", "c@test.example", Role.CLAIMANT, india);
        officer = new AppUser("officer.in", "hash", "Officer India", "o@test.example", Role.OFFICER, india);
    }

    private Claim submittedClaim() {
        return new Claim("CLM-10001", ClaimType.MOTOR, ClaimStatus.SUBMITTED, "MOT-IN-0001",
                new BigDecimal("1500000.00"), new BigDecimal("5000.00"), claimant, india, "INR",
                new BigDecimal("45000.00"), LocalDate.of(2026, 2, 20), "MG Road", "Rear-ended at a signal");
    }

    private Claim assignedClaim(ClaimStatus status) {
        Claim claim = submittedClaim();
        claim.setStatus(status);
        claim.setAssignedOfficer(officer);
        return claim;
    }

    // ---- payload per event type ---------------------------------------------------------------------------

    @Test
    void submittedCarriesTheCommonFieldsAndTheClaimedAmount() {
        DomainEvent event = events.submitted(submittedClaim());

        assertThat(event.eventType()).isEqualTo("ClaimSubmitted");
        assertThat(event.payload()).containsEntry("claimNumber", "CLM-10001").containsEntry("claimType", "MOTOR")
                .containsEntry("status", "SUBMITTED").containsEntry("marketCode", "IN")
                .containsEntry("claimantUserId", "claimant.in")
                .containsEntry("claimedAmount", new BigDecimal("45000.00")).containsEntry("currencyCode", "INR");
        assertThat(event.payload()).doesNotContainKey("assignedOfficerUserId");
    }

    @Test
    void assignedCarriesTheOfficer() {
        DomainEvent event = events.assigned(assignedClaim(ClaimStatus.UNDER_REVIEW));

        assertThat(event.eventType()).isEqualTo("ClaimAssigned");
        assertThat(event.payload()).containsEntry("status", "UNDER_REVIEW")
                .containsEntry("assignedOfficerUserId", "officer.in");
    }

    @Test
    void informationRequestedAndProvidedCarryTheCommonFields() {
        DomainEvent requested = events.informationRequested(assignedClaim(ClaimStatus.PENDING_INFORMATION));
        DomainEvent provided = events.informationProvided(assignedClaim(ClaimStatus.UNDER_REVIEW));

        assertThat(requested.eventType()).isEqualTo("InformationRequested");
        assertThat(requested.payload()).containsEntry("status", "PENDING_INFORMATION")
                .containsEntry("assignedOfficerUserId", "officer.in");
        assertThat(provided.eventType()).isEqualTo("InformationProvided");
        assertThat(provided.payload()).containsEntry("status", "UNDER_REVIEW")
                .containsEntry("assignedOfficerUserId", "officer.in");
    }

    @Test
    void escalatedCarriesTheNewStatus() {
        DomainEvent event = events.escalated(assignedClaim(ClaimStatus.ESCALATED));

        assertThat(event.eventType()).isEqualTo("ClaimEscalated");
        assertThat(event.payload()).containsEntry("status", "ESCALATED");
    }

    @Test
    void approvedCarriesTheApprovedAmountAndTheCurrency() {
        Claim claim = assignedClaim(ClaimStatus.APPROVED);
        claim.setApprovedAmount(new BigDecimal("40000.00"));

        DomainEvent event = events.approved(claim);

        assertThat(event.eventType()).isEqualTo("ClaimApproved");
        assertThat(event.payload()).containsEntry("approvedAmount", new BigDecimal("40000.00"))
                .containsEntry("currencyCode", "INR").containsEntry("status", "APPROVED");
    }

    @Test
    void rejectedCarriesTheReasonCode() {
        Claim claim = assignedClaim(ClaimStatus.REJECTED);
        claim.setDecisionReason(RejectionReason.NOT_COVERED);

        DomainEvent event = events.rejected(claim);

        assertThat(event.eventType()).isEqualTo("ClaimRejected");
        assertThat(event.payload()).containsEntry("reasonCode", "NOT_COVERED").containsEntry("status", "REJECTED");
    }

    @Test
    void settledCarriesTheAmountTheCurrencyAndThePaymentReference() {
        Claim claim = assignedClaim(ClaimStatus.SETTLED);
        claim.setSettledAmount(new BigDecimal("40000.00"));
        claim.setPaymentReference("PAY-0123456789AB");

        DomainEvent event = events.settled(claim);

        assertThat(event.eventType()).isEqualTo("ClaimSettled");
        assertThat(event.payload()).containsEntry("settledAmount", new BigDecimal("40000.00"))
                .containsEntry("currencyCode", "INR").containsEntry("paymentReference", "PAY-0123456789AB");
    }

    // ---- properties of every event --------------------------------------------------------------------------

    private static final List<Function<ClaimEventsTest, DomainEvent>> EVERY_EVENT_WITH_ALL_DATA = List.of(
            t -> t.events.submitted(t.fullyDecidedClaim()),
            t -> t.events.assigned(t.fullyDecidedClaim()),
            t -> t.events.informationRequested(t.fullyDecidedClaim()),
            t -> t.events.informationProvided(t.fullyDecidedClaim()),
            t -> t.events.escalated(t.fullyDecidedClaim()),
            t -> t.events.approved(t.fullyDecidedClaim()),
            t -> t.events.rejected(t.fullyDecidedClaim()),
            t -> t.events.settled(t.fullyDecidedClaim()));

    private Claim fullyDecidedClaim() {
        Claim claim = assignedClaim(ClaimStatus.APPROVED);
        claim.setApprovedAmount(new BigDecimal("40000.00"));
        claim.setDecisionReason(RejectionReason.NOT_COVERED);
        claim.setSettledAmount(new BigDecimal("40000.00"));
        claim.setPaymentReference("PAY-0123456789AB");
        return claim;
    }

    static List<Function<ClaimEventsTest, DomainEvent>> everyEventWithAllData() {
        return EVERY_EVENT_WITH_ALL_DATA;
    }

    @ParameterizedTest
    @MethodSource("everyEventWithAllData")
    void everyEventHasTheEnvelopeFieldsAndNoNullValue(Function<ClaimEventsTest, DomainEvent> build) {
        DomainEvent event = build.apply(this);

        assertThat(event.eventId()).isNotBlank();
        assertThat(event.aggregateType()).isEqualTo("Claim");
        assertThat(event.aggregateId()).isEqualTo("CLM-10001");
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.payload()).containsEntry("claimNumber", "CLM-10001");
        assertThat(event.payload().values()).doesNotContainNull();
    }

    @Test
    void optionalFieldsAreLeftOutRatherThanSentAsNullWhenTheClaimHasNoValueForThem() {
        Claim bare = submittedClaim();

        for (DomainEvent event : List.of(events.approved(bare), events.rejected(bare), events.settled(bare),
                events.assigned(bare))) {
            assertThat(event.payload().values()).doesNotContainNull();
        }
        assertThat(events.approved(bare).payload()).doesNotContainKey("approvedAmount");
        assertThat(events.rejected(bare).payload()).doesNotContainKey("reasonCode");
        assertThat(events.settled(bare).payload()).doesNotContainKeys("settledAmount", "paymentReference");
    }

    @Test
    void theEventTypeNamesAreTheDocumentedOnes() {
        assertThat(List.of(EventTypes.CLAIM_SUBMITTED, EventTypes.CLAIM_ASSIGNED, EventTypes.INFORMATION_REQUESTED,
                EventTypes.INFORMATION_PROVIDED, EventTypes.CLAIM_ESCALATED, EventTypes.CLAIM_APPROVED,
                EventTypes.CLAIM_REJECTED, EventTypes.CLAIM_SETTLED))
                .containsExactly("ClaimSubmitted", "ClaimAssigned", "InformationRequested", "InformationProvided",
                        "ClaimEscalated", "ClaimApproved", "ClaimRejected", "ClaimSettled");
    }

    @Test
    void eachEventGetsItsOwnId() {
        Claim claim = assignedClaim(ClaimStatus.UNDER_REVIEW);

        assertThat(events.assigned(claim).eventId()).isNotEqualTo(events.assigned(claim).eventId());
    }

    @Test
    void thePayloadKeepsTheCommonFieldsFirstInAStableOrder() {
        Map<String, Object> payload = events.submitted(submittedClaim()).payload();

        assertThat(payload.keySet()).containsExactly("claimNumber", "claimType", "status", "marketCode",
                "claimantUserId", "claimedAmount", "currencyCode");
    }
}
