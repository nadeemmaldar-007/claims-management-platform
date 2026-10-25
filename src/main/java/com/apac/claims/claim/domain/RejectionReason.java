package com.apac.claims.claim.domain;

/** Reason code staff must give when rejecting a claim; it is shown to the claimant. */
public enum RejectionReason {
    POLICY_INACTIVE,
    NOT_COVERED,
    FRAUD_SUSPECTED,
    INSUFFICIENT_EVIDENCE,
    DUPLICATE_CLAIM,
    INFORMATION_NOT_PROVIDED,
    OTHER
}
