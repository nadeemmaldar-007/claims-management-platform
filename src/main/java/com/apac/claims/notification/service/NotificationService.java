package com.apac.claims.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.notification.api.dto.NotificationResponse;
import com.apac.claims.notification.domain.Notification;
import com.apac.claims.notification.domain.NotificationRepository;
import com.apac.claims.notification.domain.NotificationType;
import com.apac.claims.shared.api.PageResponse;
import com.apac.claims.shared.exception.ResourceNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates notifications for the event handler and serves them to their owners. Creating one is idempotent: the pair
 * (source event, recipient) is unique, a notification that exists is not created again, and a duplicate that slips
 * through the check (two deliveries at the same moment) is recognised by the unique constraint and ignored. The insert
 * runs in a transaction of its own, because on PostgreSQL a violated constraint would otherwise leave the surrounding
 * transaction of the relay unusable. Every notification that is created is also passed to the channel; a channel that
 * fails is logged and does not matter.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final ObjectProvider<NotificationChannel> channel;
    private final Clock clock;
    private final TransactionTemplate insertTransaction;

    public NotificationService(NotificationRepository notifications, ObjectProvider<NotificationChannel> channel,
                               Clock clock, PlatformTransactionManager transactionManager) {
        this.notifications = notifications;
        this.channel = channel;
        this.clock = clock;
        this.insertTransaction = new TransactionTemplate(transactionManager);
        this.insertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @return true when the notification was created, false when this event had already notified this recipient
     */
    public boolean deliver(String sourceEventId, AppUser recipient, String claimNumber, NotificationType type,
                           String title, String message) {
        if (notifications.existsBySourceEventIdAndRecipient(sourceEventId, recipient)) {
            return false;
        }
        Notification created;
        try {
            created = insertTransaction.execute(status -> notifications.saveAndFlush(
                    new Notification(recipient, claimNumber, type, title, message, sourceEventId)));
        } catch (DataIntegrityViolationException e) {
            log.info("Notification already delivered event={} recipient={}", sourceEventId, recipient.getUserId());
            return false;
        }
        send(created, recipient);
        return true;
    }

    private void send(Notification created, AppUser recipient) {
        NotificationChannel available = channel.getIfAvailable();
        if (available == null) {
            return;
        }
        try {
            available.send(created, recipient);
        } catch (RuntimeException e) {
            log.warn("The notification channel failed for claim={} type={}: {}", created.getClaimNumber(),
                    created.getNotificationType(), e.toString());
        }
    }

    // ---- the owner's side ---------------------------------------------------------------------------------------------

    /** The user's own notifications; the order is set by the pageable (newest first in the controller). */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(AppUser user, boolean unreadOnly, Pageable pageable) {
        return PageResponse.of(unreadOnly
                        ? notifications.findByRecipientAndReadFlagFalse(user, pageable)
                        : notifications.findByRecipient(user, pageable),
                NotificationService::toResponse);
    }

    @Transactional(readOnly = true)
    public long unreadCount(AppUser user) {
        return notifications.countByRecipientAndReadFlagFalse(user);
    }

    /**
     * Marks one notification as read. Someone else's notification and an id that does not exist look the same (404).
     * Reading one that is already read changes nothing.
     */
    @Transactional
    public NotificationResponse markRead(AppUser user, long id) {
        Notification notification = notifications.findById(id)
                .filter(found -> found.getRecipient().getId().equals(user.getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Notification", id));
        if (!notification.isReadFlag()) {
            notification.setReadFlag(true);
            notification.setReadAt(now());
        }
        return toResponse(notification);
    }

    /** @return how many notifications changed from unread to read */
    @Transactional
    public int markAllRead(AppUser user) {
        return notifications.markAllRead(user, now());
    }

    /** The clock's time cut to microseconds, which is what the database keeps, so a response shows what is stored. */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getClaimNumber(),
                notification.getNotificationType(), notification.getTitle(), notification.getMessage(),
                notification.isReadFlag(), notification.getCreatedAt(), notification.getReadAt());
    }
}
