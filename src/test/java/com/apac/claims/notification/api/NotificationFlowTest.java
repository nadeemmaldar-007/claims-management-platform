package com.apac.claims.notification.api;

import static com.apac.claims.support.ClaimTestSupport.expect;
import static com.apac.claims.support.ClaimTestSupport.path;
import static com.apac.claims.support.ClaimTestSupport.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.apac.claims.identity.domain.Role;
import com.apac.claims.integration.service.OutboxRelay;
import com.apac.claims.support.AbstractClaimApiTest;
import com.apac.claims.support.ClaimTestSupport.Account;
import com.apac.claims.support.ClaimTestSupport.Holder;
import com.apac.claims.support.OutboxBacklog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * From a claim to its notifications: the journey runs through the API, the relay is run by the test (the scheduler is
 * off in the test profile), and the notifications are read through the API as the people concerned. The test parks the
 * backlog of earlier tests first and asserts only about its own claim.
 */
class NotificationFlowTest extends AbstractClaimApiTest {

    @Autowired
    private OutboxRelay relay;

    @BeforeEach
    void parkTheBacklog() {
        OutboxBacklog.park(jdbc);
    }

    /** Runs the relay until a batch delivers nothing, at most 50 times. */
    private void drain() {
        for (int loop = 0; loop < 50; loop++) {
            if (relay.processBatch() == 0) {
                return;
            }
        }
    }

    /** The notifications of the account about the claim, newest first, read through the API. */
    private List<Map<String, Object>> notificationsAbout(Account account, String claimNumber) throws Exception {
        MvcResult page = support.get(account, "/api/v1/notifications?size=50");
        expect(page, 200);
        return read(page, "$.content[?(@.claimNumber=='" + claimNumber + "')]");
    }

    private static List<String> types(List<Map<String, Object>> notifications) {
        return notifications.stream().map(row -> (String) row.get("type")).toList();
    }

    @Test
    void aClaimThatIsSubmittedPickedUpAnsweredAndApprovedNotifiesTheClaimantAndTheOfficer() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.submitMotor(holder);
        expect(support.post(officer, path(claimNumber, "/pick-up"), null), 200);
        support.assess(claimNumber, officer, new BigDecimal("44000.00"), true, "APPROVE");
        long requestId = support.requestInformation(claimNumber, officer);
        support.respond(claimNumber, requestId, holder.account());
        expect(support.post(officer, path(claimNumber, "/approve"), null), 200);

        drain();

        List<Map<String, Object>> toClaimant = notificationsAbout(holder.account(), claimNumber);
        assertThat(types(toClaimant)).containsExactlyInAnyOrder("CLAIM_SUBMITTED", "CLAIM_ASSIGNED",
                "INFORMATION_REQUESTED", "CLAIM_APPROVED");
        Map<String, Object> approved = toClaimant.stream().filter(row -> "CLAIM_APPROVED".equals(row.get("type")))
                .findFirst().orElseThrow();
        assertThat((String) approved.get("message")).isEqualTo("Your claim " + claimNumber
                + " was approved for 43000.00 INR.");
        assertThat((Boolean) approved.get("read")).isFalse();
        List<Map<String, Object>> toOfficer = notificationsAbout(officer, claimNumber);
        assertThat(types(toOfficer)).containsExactlyInAnyOrder("NEW_CLAIM_IN_QUEUE", "INFORMATION_PROVIDED");
        // Nothing of the assessment reaches either of them.
        assertThat(toClaimant.toString() + toOfficer).doesNotContain("Internal notes");
        // The unread count of the claimant includes the four, and they can be read in one go.
        long unread = ((Number) read(support.get(holder.account(), "/api/v1/notifications/unread-count"),
                "$.unreadCount")).longValue();
        assertThat(unread).isEqualTo(4);
        MvcResult readAll = support.post(holder.account(), "/api/v1/notifications/read-all", null);
        assertThat((Integer) read(readAll, "$.updated")).isEqualTo(4);
    }

    @Test
    void anEscalatedClaimIsReportedToTheManagersAndNeutrallyToTheClaimant() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.escalated(holder, officer);

        drain();

        Account manager = support.manager();
        List<Map<String, Object>> toManager = notificationsAbout(manager, claimNumber);
        assertThat(types(toManager)).contains("CLAIM_ESCALATED");
        List<Map<String, Object>> toClaimant = notificationsAbout(holder.account(), claimNumber);
        Map<String, Object> neutral = toClaimant.stream().filter(row -> "CLAIM_ESCALATED".equals(row.get("type")))
                .findFirst().orElseThrow();
        assertThat((String) neutral.get("message")).contains("reviewed by a manager");
        assertThat(types(notificationsAbout(officer, claimNumber))).doesNotContain("CLAIM_ESCALATED");
    }

    @Test
    void aRejectionTellsTheClaimantWhyInPlainWords() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.rejected(holder, officer);

        drain();

        Map<String, Object> rejected = notificationsAbout(holder.account(), claimNumber).stream()
                .filter(row -> "CLAIM_REJECTED".equals(row.get("type"))).findFirst().orElseThrow();
        assertThat((String) rejected.get("message")).contains("the loss is not covered by the policy")
                .doesNotContain("NOT_COVERED");
    }

    @Test
    void deliveringTheSameEventsAgainCreatesNoSecondNotification() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.approved(holder, officer);
        drain();
        int claimantBefore = notificationsAbout(holder.account(), claimNumber).size();
        int officerBefore = notificationsAbout(officer, claimNumber).size();
        assertThat(claimantBefore).isGreaterThanOrEqualTo(3);

        // Put every event of the claim back to PENDING, as a relay that crashed after the handler but before the
        // commit would have left them, and deliver them all a second time.
        int reset = jdbc.update("update outbox_events set status = 'PENDING', processed_at = null "
                + "where aggregate_id = ?", claimNumber);
        drain();

        assertThat(reset).isGreaterThanOrEqualTo(3);
        assertThat(notificationsAbout(holder.account(), claimNumber)).hasSize(claimantBefore);
        assertThat(notificationsAbout(officer, claimNumber)).hasSize(officerBefore);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and status = "
                + "'PROCESSED'", Integer.class, claimNumber)).isEqualTo(reset);
    }

    @Test
    void aClaimantNeverSeesTheNotificationsOfTheStaffAndTheOtherWayRound() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.submitMotor(holder);

        drain();

        assertThat(types(notificationsAbout(holder.account(), claimNumber))).containsExactly("CLAIM_SUBMITTED");
        assertThat(types(notificationsAbout(officer, claimNumber))).containsExactly("NEW_CLAIM_IN_QUEUE");
    }
}
