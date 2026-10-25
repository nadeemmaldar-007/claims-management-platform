package com.apac.claims.policy.domain;

/** Lifecycle state of a policy; only an ACTIVE policy that covers the incident date accepts a claim. */
public enum PolicyStatus {
    ACTIVE,
    LAPSED,
    CANCELLED
}
