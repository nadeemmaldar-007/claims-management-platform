package com.apac.claims.notification.service;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.notification.domain.Notification;

/**
 * Port to the outside world for a notification that was just created, such as an email. The adapter in
 * {@code notification.infrastructure} only logs, because no mail server is available. An implementation must not throw:
 * the notification is already stored, and a failing channel must never fail the handling of an event.
 */
public interface NotificationChannel {

    void send(Notification notification, AppUser recipient);
}
