package com.apac.claims.claim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The next claim number for one claim type. It is read under a pessimistic write lock and incremented in the same
 * transaction that inserts the claim, so numbers are unique and a rollback leaves no gap.
 */
@Entity
@Table(name = "claim_counters")
public class ClaimCounter {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false, length = 20)
    private ClaimType claimType;

    @Column(name = "next_value", nullable = false)
    private long nextValue;

    protected ClaimCounter() {
    }

    public ClaimCounter(ClaimType claimType, long nextValue) {
        this.claimType = claimType;
        this.nextValue = nextValue;
    }

    public ClaimType getClaimType() {
        return claimType;
    }

    public long getNextValue() {
        return nextValue;
    }

    public void setNextValue(long nextValue) {
        this.nextValue = nextValue;
    }
}
