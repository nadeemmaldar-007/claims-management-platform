package com.apac.claims.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.notification.domain.Notification;
import com.apac.claims.notification.domain.NotificationRepository;
import com.apac.claims.notification.domain.NotificationType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The creation of a notification without a database: the check for an existing one, the duplicate that slips past
 * that check, and a channel that fails or is not there at all.
 */
class NotificationServiceTest {

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<NotificationChannel> channelProvider = mock(ObjectProvider.class);
    private final NotificationChannel channel = mock(NotificationChannel.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final AppUser recipient = new AppUser("claimant.in", "hash", "Claimant", "claimant.in@claims-demo.example",
            Role.CLAIMANT, null);

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(notifications, channelProvider,
                Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC), transactionManager);
        when(channelProvider.getIfAvailable()).thenReturn(channel);
        when(notifications.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private boolean deliver() {
        return service.deliver("event-1", recipient, "CLM-10001", NotificationType.CLAIM_APPROVED, "Claim approved",
                "Your claim CLM-10001 was approved.");
    }

    @Test
    void aNewNotificationIsStoredAndPassedToTheChannelOnce() {
        assertThat(deliver()).isTrue();

        verify(notifications).saveAndFlush(any(Notification.class));
        verify(channel).send(any(Notification.class), eq(recipient));
    }

    @Test
    void aNotificationThatAlreadyExistsIsNotCreatedAgainAndNotSentAgain() {
        when(notifications.existsBySourceEventIdAndRecipient("event-1", recipient)).thenReturn(true);

        assertThat(deliver()).isFalse();

        verify(notifications, never()).saveAndFlush(any(Notification.class));
        verify(channel, never()).send(any(Notification.class), any(AppUser.class));
    }

    @Test
    void aDuplicateThatSlipsPastTheCheckIsRecognisedByTheConstraintAndIgnored() {
        when(notifications.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("uq_notifications_source_event_id_recipient_id"));

        assertThat(deliver()).isFalse();

        verify(channel, never()).send(any(Notification.class), any(AppUser.class));
    }

    @Test
    void aChannelThatFailsDoesNotFailTheDeliveryAndTheNotificationStaysCreated() {
        doThrow(new IllegalStateException("mail server down")).when(channel).send(any(Notification.class),
                any(AppUser.class));

        assertThatCode(this::deliver).doesNotThrowAnyException();
        assertThat(deliver()).isTrue();

        verify(notifications, times(2)).saveAndFlush(any(Notification.class));
    }

    @Test
    void withoutAChannelTheNotificationIsStillCreated() {
        when(channelProvider.getIfAvailable()).thenReturn(null);

        assertThat(deliver()).isTrue();

        verify(notifications).saveAndFlush(any(Notification.class));
    }
}
