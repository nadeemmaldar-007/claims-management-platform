package com.apac.claims.claim.domain;

/** State of a request for information; "overdue" is computed when read and is not a stored state. */
public enum InformationRequestStatus {
    OPEN,
    RESPONDED,
    CANCELLED
}
