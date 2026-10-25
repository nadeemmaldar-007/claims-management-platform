package com.apac.claims.notification.domain;

import com.apac.claims.identity.domain.AppUser;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for notifications. Ordering comes from the {@link Pageable}, so callers choose the sort. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipient(AppUser recipient, Pageable pageable);

    Page<Notification> findByRecipientAndReadFlagFalse(AppUser recipient, Pageable pageable);

    long countByRecipientAndReadFlagFalse(AppUser recipient);

    /** Idempotency check used by the event handler before inserting. */
    boolean existsBySourceEventIdAndRecipient(String sourceEventId, AppUser recipient);
}
