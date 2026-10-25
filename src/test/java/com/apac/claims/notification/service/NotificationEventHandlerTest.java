package com.apac.claims.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.shared.event.DomainEvent;
import com.apac.claims.shared.event.EventTypes;
import com.apac.claims.support.AbstractApiTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The notification rules of the specification, with events built by hand and handed straight to the handler: who is
 * told about which event, with which text. The database is shared with every other test and other tests create
 * officers and managers, so the expected recipients are read from the database at the time instead of being counted.
 */
class NotificationEventHandlerTest extends AbstractApiTest {

    private static final String PASSWORD = "Test@12345";

    @Autowired
    private NotificationEventHandler handler;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository userRepository;

    private static String newClaimNumber() {
        return "CLM-T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    /** The payload every event carries; the claim is a motor claim of the seeded Indian claimant. */
    private static Map<String, Object> payload(String claimNumber, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("claimNumber", claimNumber);
        payload.put("claimType", "MOTOR");
        payload.put("status", status);
        payload.put("marketCode", "IN");
        payload.put("claimantUserId", SEEDED_CLAIMANT);
        return payload;
    }

    private static DomainEvent event(String type, String claimNumber, Map<String, Object> payload) {
        return DomainEvent.of(Clock.systemUTC(), type, "Claim", claimNumber, payload);
    }

    /** The notifications the event produced, one map per notification. */
    private List<Map<String, Object>> notificationsOf(DomainEvent event) {
        return jdbc.queryForList("select u.user_id as recipient, n.notification_type as type, n.title, n.message, "
                + "n.claim_number as claim from notifications n join app_users u on u.id = n.recipient_id "
                + "where n.source_event_id = ?", event.eventId());
    }

    private static Set<String> recipients(List<Map<String, Object>> notifications) {
        return notifications.stream().map(row -> (String) row.get("recipient")).collect(Collectors.toSet());
    }

    private static Map<String, Object> of(List<Map<String, Object>> notifications, String recipient) {
        return notifications.stream().filter(row -> recipient.equals(row.get("recipient"))).findFirst().orElseThrow();
    }

    private Set<String> userIds(List<AppUser> users) {
        return users.stream().map(AppUser::getUserId).collect(Collectors.toSet());
    }

    // ---- who is told about which event ---------------------------------------------------------------------------------

    @Test
    void aSubmittedClaimIsAcknowledgedToTheClaimantAndAnnouncedToEveryActiveOfficerOfTheMarket() {
        AppUser newOfficer = createUser(Role.OFFICER, "IN", PASSWORD, true);
        AppUser inactiveOfficer = createUser(Role.OFFICER, "IN", PASSWORD, false);
        AppUser officerSg = createUser(Role.OFFICER, "SG", PASSWORD, true);
        String claim = newClaimNumber();
        Map<String, Object> payload = payload(claim, "SUBMITTED");
        payload.put("claimedAmount", new BigDecimal("45000.00"));
        payload.put("currencyCode", "INR");
        DomainEvent event = event(EventTypes.CLAIM_SUBMITTED, claim, payload);

        handler.handle(event);

        List<Map<String, Object>> sent = notificationsOf(event);
        Set<String> expected = userIds(userRepository.findByRoleAndMarketCodeAndActiveTrue(Role.OFFICER, "IN"));
        expected.add(SEEDED_CLAIMANT);
        assertThat(recipients(sent)).isEqualTo(expected)
                .contains(newOfficer.getUserId(), SEEDED_OFFICER, SEEDED_CLAIMANT)
                .doesNotContain(inactiveOfficer.getUserId(), officerSg.getUserId(), SEEDED_MANAGER);
        Map<String, Object> toClaimant = of(sent, SEEDED_CLAIMANT);
        assertThat(toClaimant.get("type")).isEqualTo("CLAIM_SUBMITTED");
        assertThat((String) toClaimant.get("message")).contains(claim).contains("45000.00 INR");
        Map<String, Object> toOfficer = of(sent, newOfficer.getUserId());
        assertThat(toOfficer.get("type")).isEqualTo("NEW_CLAIM_IN_QUEUE");
        assertThat((String) toOfficer.get("message")).contains(claim).contains("motor").contains("45000.00 INR")
                .contains("market IN");
    }

    @Test
    void anAssignedClaimIsReportedToTheClaimantOnly() {
        String claim = newClaimNumber();
        Map<String, Object> payload = payload(claim, "UNDER_REVIEW");
        payload.put("assignedOfficerUserId", SEEDED_OFFICER);
        DomainEvent event = event(EventTypes.CLAIM_ASSIGNED, claim, payload);

        handler.handle(event);

        List<Map<String, Object>> sent = notificationsOf(event);
        assertThat(recipients(sent)).containsExactly(SEEDED_CLAIMANT);
        assertThat(sent.get(0).get("type")).isEqualTo("CLAIM_ASSIGNED");
        assertThat((String) sent.get(0).get("message")).contains(claim);
    }

    @Test
    void aRequestForInformationIsReportedToTheClaimantOnly() {
        String claim = newClaimNumber();
        DomainEvent event = event(EventTypes.INFORMATION_REQUESTED, claim, payload(claim, "PENDING_INFORMATION"));

        handler.handle(event);

        List<Map<String, Object>> sent = notificationsOf(event);
        assertThat(recipients(sent)).containsExactly(SEEDED_CLAIMANT);
        assertThat(sent.get(0).get("type")).isEqualTo("INFORMATION_REQUESTED");
        assertThat((String) sent.get(0).get("message")).contains(claim).contains("answer the question");
    }

    @Test
    void anAnswerIsReportedToTheAssignedOfficerOnly() {
        String claim = newClaimNumber();
        Map<String, Object> payload = payload(claim, "UNDER_REVIEW");
        payload.put("assignedOfficerUserId", SEEDED_OFFICER);
        DomainEvent event = event(EventTypes.INFORMATION_PROVIDED, claim, payload);

        handler.handle(event);

        List<Map<String, Object>> sent = notificationsOf(event);
        assertThat(recipients(sent)).containsExactly(SEEDED_OFFICER);
        assertThat(sent.get(0).get("type")).isEqualTo("INFORMATION_PROVIDED");
        assertThat((String) sent.get(0).get("message")).contains(claim);
    }

    @Test
    void anEscalationIsReportedToEveryActiveManagerAndNeutrallyToTheClaimant() {
        AppUser newManager = createUser(Role.MANAGER, null, PASSWORD, true);
        AppUser inactiveManager = createUser(Role.MANAGER, null, PASSWORD, false);
        String claim = newClaimNumber();
        Map<String, Object> payload = payload(claim, "ESCALATED");
        payload.put("assignedOfficerUserId", SEEDED_OFFICER);
        DomainEvent event = event(EventTypes.CLAIM_ESCALATED, claim, payload);

        handler.handle(event);

        List<Map<String, Object>> sent = notificationsOf(event);
        Set<String> expected = userIds(userRepository.findByRoleAndActiveTrue(Role.MANAGER));
        expected.add(SEEDED_CLAIMANT);
        assertThat(recipients(sent)).isEqualTo(expected).contains(SEEDED_MANAGER, newManager.getUserId())
                .doesNotContain(inactiveManager.getUserId(), SEEDED_OFFICER);
        assertThat((String) of(sent, SEEDED_MANAGER).get("message")).contains(claim).contains("manager's decision");
        Map<String, Object> toClaimant = of(sent, SEEDED_CLAIMANT);
        assertThat((String) toClaimant.get("message")).contains(claim).contains("reviewed by a manager")
                .doesNotContain("decision").doesNotContain("escalat");
        assertThat((String) toClaimant.get("title")).doesNotContainIgnoringCase("escalat");
    }

    @ParameterizedTest
    @CsvSource({"INR, 43000, 43000.00 INR", "SGD, 24900.5, 24900.50 SGD", "JPY, 5000, 5000 JPY"})
    void anApprovalTellsTheClaimantTheAmountWithTheDecimalPlacesOfTheCurrency(String currency, String amount,
                                                                              String shown) {
        String claim = newClaimNumber();
        Map<String, Object> payload = payload(claim, "APPROVED");
        payload.put("approvedAmount", new BigDecimal(amount));
        payload.put("currencyCode", currency);
        DomainEvent event = event(EventTypes.CLAIM_APPROVED, claim, payload);

        handler.handle(event);

        List<Map<String, Object>> sent = notificationsOf(event);
        assertThat(recipients(sent)).containsExactly(SEEDED_CLAIMANT);
        assertThat(sent.get(0).get("type")).isEqualTo("CLAIM_APPROVED");
        assertThat((String) sent.get(0).get("message")).isEqualTo("Your claim " + claim + " was approved for " + shown
                + ".");
    }

    @ParameterizedTest
    @CsvSource({
            "POLICY_INACTIVE, the policy was not active",
            "NOT_COVERED, the loss is not covered by the policy",
            "FRAUD_SUSPECTED, the claim did not pass our verification checks",
            "INSUFFICIENT_EVIDENCE, there was not enough evidence",
            "DUPLICATE_CLAIM, it repeats another claim",
            "INFORMATION_NOT_PROVIDED, the information we asked for was not provided",
            "OTHER, for other reasons"})
    void aRejectionGivesTheReasonInPlainWordsNotAsACode(String code, String words) {
        String claim = newClaimNumber();
        Map<String, Object> payload = payload(claim, "REJECTED");
        payload.put("reasonCode", code);
        DomainEvent event = event(EventTypes.CLAIM_REJECTED, claim, payload);

        handler.handle(event);

        List<Map<String, Object>> sent = notificationsOf(event);
        assertThat(recipients(sent)).containsExactly(SEEDED_CLAIMANT);
        assertThat(sent.get(0).get("type")).isEqualTo("CLAIM_REJECTED");
        assertThat((String) sent.get(0).get("message")).contains(claim).contains(words).doesNotContain(code);
    }

    @Test
    void aSettlementTellsTheClaimantTheAmountAndThePaymentReference() {
        String claim = newClaimNumber();
        Map<String, Object> payload = payload(claim, "SETTLED");
        payload.put("settledAmount", new BigDecimal("43000.00"));
        payload.put("currencyCode", "INR");
        payload.put("paymentReference", "PAY-0123456789AB");
        DomainEvent event = event(EventTypes.CLAIM_SETTLED, claim, payload);

        handler.handle(event);

        List<Map<String, Object>> sent = notificationsOf(event);
        assertThat(recipients(sent)).containsExactly(SEEDED_CLAIMANT);
        assertThat(sent.get(0).get("type")).isEqualTo("CLAIM_SETTLED");
        assertThat((String) sent.get(0).get("message")).contains("43000.00 INR").contains("PAY-0123456789AB");
    }

    // ---- what the handler does not do ------------------------------------------------------------------------------------

    @Test
    void anEventTypeWithoutARuleIsIgnored() {
        String claim = newClaimNumber();
        DomainEvent event = event("SomethingElseHappened", claim, payload(claim, "SUBMITTED"));

        handler.handle(event);

        assertThat(notificationsOf(event)).isEmpty();
    }

    @Test
    void aRecipientWhoDoesNotExistIsSkippedAndTheEventDoesNotFail() {
        String claim = newClaimNumber();
        Map<String, Object> payload = payload(claim, "UNDER_REVIEW");
        payload.put("claimantUserId", "nobody.at.all");
        payload.put("assignedOfficerUserId", "nobody.either");

        DomainEvent assigned = event(EventTypes.CLAIM_ASSIGNED, claim, payload);
        DomainEvent provided = event(EventTypes.INFORMATION_PROVIDED, claim, payload);
        handler.handle(assigned);
        handler.handle(provided);

        assertThat(notificationsOf(assigned)).isEmpty();
        assertThat(notificationsOf(provided)).isEmpty();
    }

    @Test
    void anEventWithoutAmountsStillGivesAReadableMessage() {
        String claim = newClaimNumber();
        DomainEvent approved = event(EventTypes.CLAIM_APPROVED, claim, payload(claim, "APPROVED"));
        DomainEvent settled = event(EventTypes.CLAIM_SETTLED, claim, payload(claim, "SETTLED"));

        handler.handle(approved);
        handler.handle(settled);

        assertThat((String) notificationsOf(approved).get(0).get("message"))
                .isEqualTo("Your claim " + claim + " was approved.");
        assertThat((String) notificationsOf(settled).get(0).get("message"))
                .isEqualTo("Your claim " + claim + " was paid.");
    }

    @Test
    void nothingFromAnAssessmentOrAnyOtherPayloadFieldIsCopiedIntoAText() {
        String claim = newClaimNumber();
        Map<String, Object> payload = payload(claim, "APPROVED");
        payload.put("approvedAmount", new BigDecimal("1000.00"));
        payload.put("currencyCode", "INR");
        payload.put("notes", "INTERNAL-NOTE-DO-NOT-SHOW");
        payload.put("recommendation", "INTERNAL-RECOMMENDATION");
        DomainEvent event = event(EventTypes.CLAIM_APPROVED, claim, payload);

        handler.handle(event);

        Map<String, Object> row = notificationsOf(event).get(0);
        assertThat(row.get("message").toString() + row.get("title")).doesNotContain("INTERNAL");
    }

    @Test
    void everyTitleAndTextFitsItsColumn() {
        String claim = newClaimNumber();
        Map<String, Object> payload = payload(claim, "APPROVED");
        payload.put("approvedAmount", new BigDecimal("1000.00"));
        payload.put("currencyCode", "INR");
        payload.put("reasonCode", "OTHER");
        payload.put("settledAmount", new BigDecimal("1000.00"));
        payload.put("paymentReference", "PAY-0123456789AB");
        payload.put("assignedOfficerUserId", SEEDED_OFFICER);
        payload.put("claimedAmount", new BigDecimal("1000.00"));

        for (String type : List.of(EventTypes.CLAIM_SUBMITTED, EventTypes.CLAIM_ASSIGNED,
                EventTypes.INFORMATION_REQUESTED, EventTypes.INFORMATION_PROVIDED, EventTypes.CLAIM_ESCALATED,
                EventTypes.CLAIM_APPROVED, EventTypes.CLAIM_REJECTED, EventTypes.CLAIM_SETTLED)) {
            DomainEvent event = event(type, claim, payload);
            handler.handle(event);
            for (Map<String, Object> row : notificationsOf(event)) {
                assertThat(((String) row.get("title")).length()).as(type + " title").isLessThanOrEqualTo(150);
                assertThat(((String) row.get("message")).length()).as(type + " message").isLessThanOrEqualTo(1000);
                assertThat((String) row.get("claim")).isEqualTo(claim);
            }
        }
    }

    // ---- idempotency ---------------------------------------------------------------------------------------------------------

    @Test
    void theSameEventHandledTwiceGivesOneNotificationPerRecipient() {
        String claim = newClaimNumber();
        Map<String, Object> payload = payload(claim, "SUBMITTED");
        payload.put("claimedAmount", new BigDecimal("45000.00"));
        payload.put("currencyCode", "INR");
        DomainEvent event = event(EventTypes.CLAIM_SUBMITTED, claim, payload);

        handler.handle(event);
        List<Map<String, Object>> first = notificationsOf(event);
        handler.handle(event);
        handler.handle(event);

        List<Map<String, Object>> after = notificationsOf(event);
        assertThat(after).hasSameSizeAs(first);
        assertThat(after.stream().map(row -> row.get("recipient")).distinct().count()).isEqualTo(after.size());
    }

    @Test
    void aNewOfficerWhoJoinsBetweenTwoDeliveriesIsToldOnTheRedeliveryAndNobodyIsToldTwice() {
        String claim = newClaimNumber();
        DomainEvent event = event(EventTypes.CLAIM_SUBMITTED, claim, payload(claim, "SUBMITTED"));
        handler.handle(event);
        int before = notificationsOf(event).size();

        AppUser latecomer = createUser(Role.OFFICER, "IN", PASSWORD, true);
        handler.handle(event);

        List<Map<String, Object>> after = notificationsOf(event);
        assertThat(after).hasSize(before + 1);
        assertThat(recipients(after)).contains(latecomer.getUserId());
    }
}
