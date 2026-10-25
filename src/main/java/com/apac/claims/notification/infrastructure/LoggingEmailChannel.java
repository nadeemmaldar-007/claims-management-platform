package com.apac.claims.notification.infrastructure;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.notification.domain.Notification;
import com.apac.claims.notification.service.NotificationChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The email channel of this version: it writes one INFO line and sends nothing. The address is masked ({@code
 * a***@claims-demo.example}) and neither the title nor the text of the notification is logged. It exists only when
 * {@code app.notification.email.enabled} is true, and it never throws.
 */
@Component
@ConditionalOnProperty(prefix = "app.notification.email", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LoggingEmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailChannel.class);

    @Override
    public void send(Notification notification, AppUser recipient) {
        try {
            log.info("Email notification to {} type={} claim={}", maskEmail(recipient.getEmail()),
                    notification.getNotificationType(), notification.getClaimNumber());
        } catch (RuntimeException e) {
            log.warn("The email channel could not log a notification: {}", e.toString());
        }
    }

    /** {@code anna@claims-demo.example} becomes {@code a***@claims-demo.example}; anything without an {@code @} is hidden. */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int at = email.indexOf('@');
        if (at < 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
