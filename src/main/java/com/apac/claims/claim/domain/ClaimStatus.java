package com.apac.claims.claim.domain;

/**
 * Workflow states of a claim. A claim is open until it is rejected or settled; those two are final.
 * The allowed transitions live in {@code ClaimWorkflow}, not here.
 */
public enum ClaimStatus {
    SUBMITTED,
    UNDER_REVIEW,
    PENDING_INFORMATION,
    ESCALATED,
    APPROVED,
    REJECTED,
    SETTLED;

    /** Open claims still need staff attention; an approved claim is waiting for payment and is not open. */
    public boolean isOpen() {
        return this == SUBMITTED || this == UNDER_REVIEW || this == PENDING_INFORMATION || this == ESCALATED;
    }

    /** No transition leaves a final state. */
    public boolean isFinal() {
        return this == REJECTED || this == SETTLED;
    }
}
