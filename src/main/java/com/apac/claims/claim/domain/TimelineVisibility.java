package com.apac.claims.claim.domain;

/** Who may see a timeline entry: claimants only ever see PUBLIC entries, staff see both. */
public enum TimelineVisibility {
    PUBLIC,
    INTERNAL
}
