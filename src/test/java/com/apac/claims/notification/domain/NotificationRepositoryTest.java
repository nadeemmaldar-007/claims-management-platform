package com.apac.claims.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.support.RepositoryTest;
import com.apac.claims.support.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@RepositoryTest
class NotificationRepositoryTest {

    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private UserRepository users;
    @Autowired
    private MarketRepository markets;

    private AppUser alice;
    private AppUser bob;
    private Notification aliceRead;
    private Notification aliceUnreadOne;
    private Notification aliceUnreadTwo;

    @BeforeEach
    void createNotifications() {
        Market india = markets.findByCode("IN").orElseThrow();
        alice = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        bob = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        aliceRead = TestData.notification(alice, TestData.eventId());
        aliceRead.setReadFlag(true);
        aliceRead.setReadAt(Instant.now());
        notifications.save(aliceRead);
        aliceUnreadOne = notifications.save(TestData.notification(alice, TestData.eventId()));
        aliceUnreadTwo = notifications.save(TestData.notification(alice, TestData.eventId()));
        notifications.saveAndFlush(TestData.notification(bob, TestData.eventId()));
    }

    @Test
    void theUnreadCountIsPerRecipient() {
        assertThat(notifications.countByRecipientAndReadFlagFalse(alice)).isEqualTo(2);
        assertThat(notifications.countByRecipientAndReadFlagFalse(bob)).isEqualTo(1);
    }

    @Test
    void theUnreadFilterReturnsOnlyUnreadNotificationsOfThatRecipientNewestFirst() {
        Page<Notification> page = notifications.findByRecipientAndReadFlagFalse(alice,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "id")));

        assertThat(page.getContent()).containsExactly(aliceUnreadTwo, aliceUnreadOne);
    }

    @Test
    void withoutTheFilterReadAndUnreadNotificationsAreReturned() {
        Page<Notification> page = notifications.findByRecipient(alice,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "id")));

        assertThat(page.getContent()).containsExactly(aliceRead, aliceUnreadOne, aliceUnreadTwo);
    }

    @Test
    void resultsArePagedAndReportTheTotals() {
        Page<Notification> firstPage = notifications.findByRecipient(alice,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "id")));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.isLast()).isFalse();
    }

    @Test
    void markingANotificationReadLowersTheUnreadCount() {
        aliceUnreadOne.setReadFlag(true);
        aliceUnreadOne.setReadAt(Instant.now());
        notifications.saveAndFlush(aliceUnreadOne);

        assertThat(notifications.countByRecipientAndReadFlagFalse(alice)).isEqualTo(1);
    }

    @Test
    void anEventAlreadyHandledForARecipientIsRecognised() {
        assertThat(notifications.existsBySourceEventIdAndRecipient(aliceUnreadOne.getSourceEventId(), alice)).isTrue();
    }

    @Test
    void theSameEventForAnotherRecipientIsNotConsideredHandled() {
        assertThat(notifications.existsBySourceEventIdAndRecipient(aliceUnreadOne.getSourceEventId(), bob)).isFalse();
    }

    @Test
    void anUnknownEventIsNotConsideredHandled() {
        assertThat(notifications.existsBySourceEventIdAndRecipient(TestData.eventId(), alice)).isFalse();
    }
}
