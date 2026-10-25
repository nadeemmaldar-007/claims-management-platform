package com.apac.claims.notification.domain;

import java.time.Instant;

import com.apac.claims.identity.domain.AppUser;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence port for notifications. Ordering comes from the {@link Pageable}, so callers choose the sort. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipient(AppUser recipient, Pageable pageable);

    Page<Notification> findByRecipientAndReadFlagFalse(AppUser recipient, Pageable pageable);

    long countByRecipientAndReadFlagFalse(AppUser recipient);

    /** Idempotency check used by the event handler before inserting. */
    boolean existsBySourceEventIdAndRecipient(String sourceEventId, AppUser recipient);

    /** Marks every unread notification of the recipient as read in one statement and returns how many changed. */
    @Modifying(clearAutomatically = true)
    @Query("update Notification n set n.readFlag = true, n.readAt = :now "
            + "where n.recipient = :recipient and n.readFlag = false")
    int markAllRead(@Param("recipient") AppUser recipient, @Param("now") Instant now);
}
