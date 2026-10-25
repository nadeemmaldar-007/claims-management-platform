package com.apac.claims.notification.domain;

/** The kinds of in-app notification, one or more per domain event according to the notification rules. */
public enum NotificationType {
    CLAIM_SUBMITTED,
    NEW_CLAIM_IN_QUEUE,
    CLAIM_ASSIGNED,
    INFORMATION_REQUESTED,
    INFORMATION_PROVIDED,
    CLAIM_ESCALATED,
    CLAIM_APPROVED,
    CLAIM_REJECTED,
    CLAIM_SETTLED
}
