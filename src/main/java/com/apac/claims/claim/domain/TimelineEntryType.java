package com.apac.claims.claim.domain;

/** The kinds of event recorded in a claim's audit timeline. */
public enum TimelineEntryType {
    SUBMITTED,
    ASSIGNED,
    REASSIGNED,
    ASSESSMENT_RECORDED,
    INFORMATION_REQUESTED,
    INFORMATION_PROVIDED,
    INFORMATION_REQUEST_CANCELLED,
    ESCALATED,
    APPROVED,
    REJECTED,
    SETTLED
}
