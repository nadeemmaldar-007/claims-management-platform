package com.apac.claims.notification.api;

import static com.apac.claims.support.ClaimTestSupport.code;
import static com.apac.claims.support.ClaimTestSupport.expect;
import static com.apac.claims.support.ClaimTestSupport.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.apac.claims.identity.domain.Role;
import com.apac.claims.notification.domain.Notification;
import com.apac.claims.notification.domain.NotificationRepository;
import com.apac.claims.notification.domain.NotificationType;
import com.apac.claims.support.AbstractClaimApiTest;
import com.apac.claims.support.ClaimTestSupport.Account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The notification endpoints: everybody reads and changes only their own notifications. Every test creates its own
 * accounts and its own notification rows, so no test depends on what another one left behind.
 */
class NotificationApiTest extends AbstractClaimApiTest {

    private static final String BASE = "/api/v1/notifications";

    @Autowired
    private NotificationRepository notificationRepository;

    /** Stores a notification for the account and returns its id. */
    private long notify(Account account, String claimNumber, String title) {
        Notification stored = notificationRepository.saveAndFlush(new Notification(
                users.findByUserId(account.userId()).orElseThrow(), claimNumber, NotificationType.CLAIM_ASSIGNED, title,
                "Text of " + title, UUID.randomUUID().toString()));
        return stored.getId();
    }

    private static List<String> titles(MvcResult page) throws Exception {
        return read(page, "$.content[*].title");
    }

    // ---- list ----------------------------------------------------------------------------------------------------------

    @Test
    void aUserSeesOnlyTheirOwnNotificationsNewestFirst() throws Exception {
        Account mine = support.newAccount(Role.CLAIMANT, "IN");
        Account other = support.newAccount(Role.CLAIMANT, "IN");
        notify(mine, "CLM-10001", "first");
        notify(mine, "CLM-10001", "second");
        notify(mine, "CLM-10002", "third");
        notify(other, "CLM-10003", "not mine");

        MvcResult result = support.get(mine, BASE);

        expect(result, 200);
        assertThat(titles(result)).containsExactly("third", "second", "first");
        assertThat(((Number) read(result, "$.totalElements")).longValue()).isEqualTo(3);
        assertThat((String) read(result, "$.content[0].claimNumber")).isEqualTo("CLM-10002");
        assertThat((String) read(result, "$.content[0].type")).isEqualTo("CLAIM_ASSIGNED");
        assertThat((String) read(result, "$.content[0].message")).isEqualTo("Text of third");
        assertThat((Boolean) read(result, "$.content[0].read")).isFalse();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("readAt");
    }

    @Test
    void theUnreadOnlyFilterHidesWhatWasRead() throws Exception {
        Account account = support.newAccount(Role.CLAIMANT, "IN");
        long readOne = notify(account, "CLM-10001", "already read");
        notify(account, "CLM-10001", "still unread");
        expect(support.post(account, BASE + "/" + readOne + "/read", null), 200);

        MvcResult unread = support.get(account, BASE + "?unreadOnly=true");
        MvcResult all = support.get(account, BASE + "?unreadOnly=false");

        assertThat(titles(unread)).containsExactly("still unread");
        assertThat(titles(all)).containsExactlyInAnyOrder("still unread", "already read");
    }

    @Test
    void theListIsPagedWithTheUsualRules() throws Exception {
        Account account = support.newAccount(Role.CLAIMANT, "IN");
        for (int i = 1; i <= 3; i++) {
            notify(account, "CLM-1000" + i, "n" + i);
        }

        MvcResult first = support.get(account, BASE + "?size=2&page=0");
        MvcResult second = support.get(account, BASE + "?size=2&page=1");

        assertThat(titles(first)).containsExactly("n3", "n2");
        assertThat(titles(second)).containsExactly("n1");
        assertThat((Boolean) read(first, "$.last")).isFalse();
        assertThat((Boolean) read(second, "$.last")).isTrue();
        assertThat((Integer) read(support.get(account, BASE), "$.size")).isEqualTo(10);
        assertThat((Integer) read(support.get(account, BASE + "?size=500"), "$.size")).isEqualTo(50);
    }

    @Test
    void aNegativePageOrASizeBelowOneIsABadRequest() throws Exception {
        Account account = support.newAccount(Role.CLAIMANT, "IN");

        MvcResult page = support.get(account, BASE + "?page=-1");
        MvcResult size = support.get(account, BASE + "?size=0");

        expect(page, 400);
        expect(size, 400);
        assertThat((String) read(page, "$.errors[0].field")).isEqualTo("page");
        assertThat((String) read(size, "$.errors[0].field")).isEqualTo("size");
    }

    @Test
    void everyRoleHasItsOwnNotifications() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Account manager = support.newAccount(Role.MANAGER, null);
        notify(officer, "CLM-10001", "for the officer");
        notify(manager, "CLM-10001", "for the manager");

        assertThat(titles(support.get(officer, BASE))).containsExactly("for the officer");
        assertThat(titles(support.get(manager, BASE))).containsExactly("for the manager");
    }

    // ---- unread count --------------------------------------------------------------------------------------------------

    @Test
    void theUnreadCountFollowsWhatIsReadAndNeverCountsAnotherUsersNotifications() throws Exception {
        Account account = support.newAccount(Role.CLAIMANT, "IN");
        Account other = support.newAccount(Role.CLAIMANT, "IN");
        assertThat(((Number) read(support.get(account, BASE + "/unread-count"), "$.unreadCount")).longValue()).isZero();
        long first = notify(account, "CLM-10001", "a");
        notify(account, "CLM-10001", "b");
        notify(other, "CLM-10001", "c");

        MvcResult before = support.get(account, BASE + "/unread-count");
        expect(support.post(account, BASE + "/" + first + "/read", null), 200);
        MvcResult after = support.get(account, BASE + "/unread-count");

        expect(before, 200);
        assertThat(((Number) read(before, "$.unreadCount")).longValue()).isEqualTo(2);
        assertThat(((Number) read(after, "$.unreadCount")).longValue()).isEqualTo(1);
    }

    // ---- mark read -----------------------------------------------------------------------------------------------------

    @Test
    void markingOneAsReadSetsTheFlagAndTheTimeAndDoingItAgainChangesNothing() throws Exception {
        Account account = support.newAccount(Role.CLAIMANT, "IN");
        long id = notify(account, "CLM-10001", "a");

        MvcResult first = support.post(account, BASE + "/" + id + "/read", null);
        MvcResult second = support.post(account, BASE + "/" + id + "/read", null);

        expect(first, 200);
        expect(second, 200);
        assertThat(((Number) read(first, "$.id")).longValue()).isEqualTo(id);
        assertThat((Boolean) read(first, "$.read")).isTrue();
        assertThat((String) read(first, "$.readAt")).isNotBlank();
        assertThat((String) read(second, "$.readAt")).isEqualTo((String) read(first, "$.readAt"));
    }

    @Test
    void someoneElsesNotificationAndAnUnknownIdAreBothNotFound() throws Exception {
        Account account = support.newAccount(Role.CLAIMANT, "IN");
        Account other = support.newAccount(Role.CLAIMANT, "IN");
        long foreign = notify(other, "CLM-10001", "not mine");

        MvcResult someoneElses = support.post(account, BASE + "/" + foreign + "/read", null);
        MvcResult unknown = support.post(account, BASE + "/999999999/read", null);

        expect(someoneElses, 404);
        expect(unknown, 404);
        assertThat(code(someoneElses)).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat((String) read(someoneElses, "$.title")).isEqualTo((String) read(unknown, "$.title"));
        // The other user's notification is still unread.
        assertThat((Boolean) read(support.get(other, BASE), "$.content[0].read")).isFalse();
    }

    // ---- read all ------------------------------------------------------------------------------------------------------

    @Test
    void readAllMarksOnlyTheCallersNotificationsAndReportsHowManyChanged() throws Exception {
        Account account = support.newAccount(Role.CLAIMANT, "IN");
        Account other = support.newAccount(Role.CLAIMANT, "IN");
        notify(account, "CLM-10001", "a");
        notify(account, "CLM-10002", "b");
        notify(account, "CLM-10003", "c");
        notify(other, "CLM-10004", "d");

        MvcResult first = support.post(account, BASE + "/read-all", null);
        MvcResult second = support.post(account, BASE + "/read-all", null);

        expect(first, 200);
        assertThat((Integer) read(first, "$.updated")).isEqualTo(3);
        assertThat((Integer) read(second, "$.updated")).isZero();
        assertThat(((Number) read(support.get(account, BASE + "/unread-count"), "$.unreadCount")).longValue()).isZero();
        assertThat(((Number) read(support.get(other, BASE + "/unread-count"), "$.unreadCount")).longValue())
                .isEqualTo(1);
        assertThat((Boolean) read(support.get(account, BASE), "$.content[0].read")).isTrue();
    }

    // ---- who may call ----------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"GET /api/v1/notifications", "GET /api/v1/notifications/unread-count",
            "POST /api/v1/notifications/1/read", "POST /api/v1/notifications/read-all"})
    void withoutATokenEveryEndpointIsUnauthenticated(String endpoint) throws Exception {
        String[] parts = endpoint.split(" ");

        MvcResult result = parts[0].equals("GET") ? support.get(null, parts[1]) : support.post(null, parts[1], null);

        expect(result, 401);
        assertThat(code(result)).isEqualTo("UNAUTHENTICATED");
    }
}
