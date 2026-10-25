package com.apac.claims.claim.api;

import static com.apac.claims.support.ClaimTestSupport.expect;
import static com.apac.claims.support.ClaimTestSupport.freshDate;
import static com.apac.claims.support.ClaimTestSupport.path;
import static com.apac.claims.support.ClaimTestSupport.rejectionBody;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.apac.claims.identity.domain.Role;
import com.apac.claims.integration.domain.OutboxEvent;
import com.apac.claims.integration.domain.OutboxStatus;
import com.apac.claims.support.AbstractClaimApiTest;
import com.apac.claims.support.ClaimTestSupport.Account;
import com.apac.claims.support.ClaimTestSupport.Holder;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The write side of the outbox through the API: every state change that raises an event writes it in the same
 * transaction, as a PENDING row with a complete JSON envelope, and an action that is refused writes neither an event
 * nor a timeline entry.
 */
class ClaimOutboxApiTest extends AbstractClaimApiTest {

    private List<OutboxEvent> eventsOf(String claimNumber) {
        return jdbc.queryForList("select id from outbox_events where aggregate_id = ? order by occurred_at, event_type",
                        String.class, claimNumber).stream()
                .map(id -> outbox.findById(id).orElseThrow())
                .toList();
    }

    private JsonNode envelope(OutboxEvent event) throws Exception {
        return objectMapper.readTree(event.getPayload());
    }

    private int allEvents() {
        return jdbc.queryForObject("select count(*) from outbox_events", Integer.class);
    }

    // ---- a complete journey --------------------------------------------------------------------------------------------

    @Test
    void aCompleteJourneyWritesTheExpectedEventsInOrderAsPendingRowsWithValidEnvelopes() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Account manager = support.manager();
        Holder holder = support.newHolder("IN");
        String claimNumber = support.submitMotor(holder);
        expect(support.post(officer, path(claimNumber, "/pick-up"), null), 200);
        long requestId = support.requestInformation(claimNumber, officer);
        support.respond(claimNumber, requestId, holder.account());
        support.assess(claimNumber, officer, new BigDecimal("44000.00"), true, "APPROVE");
        expect(support.post(officer, path(claimNumber, "/escalate"),
                Map.of("remarks", "Assessed payout is above my approval limit.")), 200);
        expect(support.post(manager, path(claimNumber, "/approve"), null), 200);

        List<OutboxEvent> events = eventsOf(claimNumber);

        assertThat(events).extracting(OutboxEvent::getEventType).containsExactly("ClaimSubmitted", "ClaimAssigned",
                "InformationRequested", "InformationProvided", "ClaimEscalated", "ClaimApproved");
        List<String> statuses = List.of("SUBMITTED", "UNDER_REVIEW", "PENDING_INFORMATION", "UNDER_REVIEW",
                "ESCALATED", "APPROVED");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < events.size(); i++) {
            OutboxEvent event = events.get(i);
            JsonNode envelope = envelope(event);
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(event.getAttempts()).isZero();
            assertThat(event.getProcessedAt()).isNull();
            assertThat(event.getAggregateType()).isEqualTo("Claim");
            assertThat(event.getAggregateId()).isEqualTo(claimNumber);
            assertThat(UUID.fromString(event.getId())).isNotNull();
            assertThat(ids.add(event.getId())).isTrue();
            assertThat(envelope.get("eventId").asText()).isEqualTo(event.getId());
            assertThat(envelope.get("eventType").asText()).isEqualTo(event.getEventType());
            assertThat(envelope.get("aggregateType").asText()).isEqualTo("Claim");
            assertThat(envelope.get("aggregateId").asText()).isEqualTo(claimNumber);
            assertThat(envelope.get("version").asInt()).isEqualTo(1);
            assertThat(envelope.get("occurredAt").asText()).isNotBlank();
            JsonNode payload = envelope.get("payload");
            assertThat(payload.get("claimNumber").asText()).isEqualTo(claimNumber);
            assertThat(payload.get("claimType").asText()).isEqualTo("MOTOR");
            assertThat(payload.get("marketCode").asText()).isEqualTo("IN");
            assertThat(payload.get("claimantUserId").asText()).isEqualTo(holder.account().userId());
            assertThat(payload.get("status").asText()).as("status in event %d", i).isEqualTo(statuses.get(i));
        }
    }

    @Test
    void theEventPayloadsCarryOnlyTheFieldsOfTheirEvent() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String approved = support.approved(holder, officer);
        String rejected = support.rejected(holder, officer);

        JsonNode submitted = envelope(eventsOf(approved).get(0)).get("payload");
        JsonNode assigned = envelope(eventsOf(approved).get(1)).get("payload");
        JsonNode approval = envelope(eventsOf(approved).get(2)).get("payload");
        JsonNode rejection = envelope(eventsOf(rejected).get(2)).get("payload");

        assertThat(submitted.get("claimedAmount").decimalValue()).isEqualByComparingTo("45000.00");
        assertThat(submitted.get("currencyCode").asText()).isEqualTo("INR");
        assertThat(submitted.has("assignedOfficerUserId")).isFalse();
        assertThat(assigned.get("assignedOfficerUserId").asText()).isEqualTo(officer.userId());
        assertThat(assigned.has("claimedAmount")).isFalse();
        assertThat(approval.get("approvedAmount").decimalValue()).isEqualByComparingTo("43000.00");
        assertThat(approval.get("currencyCode").asText()).isEqualTo("INR");
        assertThat(approval.has("reasonCode")).isFalse();
        assertThat(rejection.get("reasonCode").asText()).isEqualTo("NOT_COVERED");
        assertThat(rejection.has("approvedAmount")).isFalse();
    }

    @Test
    void aRejectionWritesOneClaimRejectedEvent() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.rejected(support.newHolder("IN"), officer);

        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned", "ClaimRejected");
    }

    @Test
    void assessmentsReassignmentsAndCancellationsWriteNoEvent() throws Exception {
        Account first = support.newAccount(Role.OFFICER, "IN");
        Account second = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, first);
        List<String> expected = List.of("ClaimSubmitted", "ClaimAssigned");

        support.assess(claimNumber, first, new BigDecimal("44000.00"), true, "APPROVE");
        assertThat(eventTypesOf(claimNumber)).isEqualTo(expected);
        expect(support.post(support.manager(), path(claimNumber, "/assign"), Map.of("officerUserId", second.userId())), 200);
        assertThat(eventTypesOf(claimNumber)).isEqualTo(expected);
        long id = support.requestInformation(claimNumber, second);
        expect(support.post(second, path(claimNumber, "/information-requests/" + id + "/cancel"), null), 200);

        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned", "InformationRequested");
    }

    @Test
    void theAssessmentNotesNeverLeaveTheSystemInAnEvent() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);

        for (OutboxEvent event : eventsOf(claimNumber)) {
            assertThat(event.getPayload()).doesNotContain("Internal notes").doesNotContain("recommendation");
        }
    }

    // ---- refused actions write nothing ---------------------------------------------------------------------------------------

    @Test
    void refusedActionsWriteNeitherAnEventNorATimelineEntryNorChangeTheClaim() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Account other = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, officer);
        int entries = timelineCount(claimNumber);
        int allBefore = allEvents();
        Long versionBefore = claims.findByClaimNumber(claimNumber).orElseThrow().getVersion();

        List<MvcResult> refused = List.of(
                // 409: approval needs an assessment, and the claim is already picked up
                support.post(officer, path(claimNumber, "/approve"), null),
                support.post(other, path(claimNumber, "/pick-up"), null),
                // 403: wrong people
                support.post(other, path(claimNumber, "/reject"), rejectionBody()),
                support.post(holder.account(), path(claimNumber, "/approve"), null),
                support.post(support.manager(), path(claimNumber, "/escalate"),
                        Map.of("remarks", "Assessed payout is above my approval limit.")),
                // 400: invalid bodies
                support.post(officer, path(claimNumber, "/reject"), Map.of("reasonCode", "NOT_COVERED")),
                support.post(officer, path(claimNumber, "/information-requests"), Map.of("question", "short")),
                support.post(officer, path(claimNumber, "/assessments"),
                        Map.of("assessedLossAmount", -1, "coverageConfirmed", true, "recommendation", "APPROVE")),
                // 422: an assignee of another market
                support.post(support.manager(), path(claimNumber, "/assign"),
                        Map.of("officerUserId", support.newAccount(Role.OFFICER, "SG").userId())));

        for (MvcResult result : refused) {
            assertThat(result.getResponse().getStatus()).as(result.getRequest().getRequestURI()).isGreaterThanOrEqualTo(400);
        }
        assertThat(timelineCount(claimNumber)).isEqualTo(entries);
        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned");
        assertThat(allEvents()).isEqualTo(allBefore);
        assertThat(claims.findByClaimNumber(claimNumber).orElseThrow().getVersion()).isEqualTo(versionBefore);
        assertThat(support.statusOf(claimNumber, officer)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void aFailedSubmissionWritesNoEvent() throws Exception {
        Holder holder = support.newHolder("IN", new BigDecimal("50000.00"), new BigDecimal("500.00"));
        int before = allEvents();

        expect(support.submit(holder.account(), support.motorBody(holder.motorPolicy(), new BigDecimal("50000.01"),
                freshDate()), null), 422);
        expect(support.submit(holder.account(), support.motorBody(holder.motorPolicy(), new BigDecimal("-1"),
                freshDate()), null), 400);

        assertThat(allEvents()).isEqualTo(before);
    }

    @Test
    void aRefusedActionOnADecidedClaimLeavesItsEventsAsTheyWere() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);
        List<String> events = eventTypesOf(claimNumber);
        int entries = timelineCount(claimNumber);

        expect(support.post(officer, path(claimNumber, "/approve"), null), 409);
        expect(support.post(officer, path(claimNumber, "/reject"), rejectionBody()), 409);

        assertThat(eventTypesOf(claimNumber)).isEqualTo(events);
        assertThat(timelineCount(claimNumber)).isEqualTo(entries);
    }
}
