package com.apac.claims.shared.event;

/**
 * The names of the domain events of a claim, as they are written to the outbox and read by handlers. They live in the
 * kernel so that a module that reacts to an event (the notifications) can use the name without depending on the module
 * that raises it (the claims).
 */
public final class EventTypes {

    public static final String CLAIM_SUBMITTED = "ClaimSubmitted";
    public static final String CLAIM_ASSIGNED = "ClaimAssigned";
    public static final String INFORMATION_REQUESTED = "InformationRequested";
    public static final String INFORMATION_PROVIDED = "InformationProvided";
    public static final String CLAIM_ESCALATED = "ClaimEscalated";
    public static final String CLAIM_APPROVED = "ClaimApproved";
    public static final String CLAIM_REJECTED = "ClaimRejected";
    public static final String CLAIM_SETTLED = "ClaimSettled";

    private EventTypes() {
    }
}
