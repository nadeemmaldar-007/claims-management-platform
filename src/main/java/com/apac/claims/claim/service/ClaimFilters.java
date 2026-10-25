package com.apac.claims.claim.service;

import java.time.LocalDate;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;

/**
 * The optional filters of the claim list; any of them may be null. {@code assignedTo} is {@code me} or a user id, and
 * the two dates are inclusive days, converted to UTC day boundaries by the query service.
 */
public record ClaimFilters(ClaimStatus status, ClaimType claimType, String marketCode, String assignedTo,
                           Boolean unassigned, LocalDate submittedFrom, LocalDate submittedTo) {

    /** No filter at all. */
    public static ClaimFilters none() {
        return new ClaimFilters(null, null, null, null, null, null, null);
    }
}
