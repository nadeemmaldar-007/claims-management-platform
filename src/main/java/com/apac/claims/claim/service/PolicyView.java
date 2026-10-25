package com.apac.claims.claim.service;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.apac.claims.claim.domain.ClaimType;

/**
 * What the claim module knows about a policy: just enough to validate a claim. The type is the claim module's own
 * {@link ClaimType} and the status its own enum, so the claim module never depends on the policy module; the adapter
 * that implements {@link PolicyLookup} does the translation.
 *
 * @param productType the kind of claim this policy covers
 * @param holderUserId user id of the policy holder
 */
public record PolicyView(String policyNumber, String marketCode, ClaimType productType, String holderUserId,
                         BigDecimal sumInsured, BigDecimal deductible, LocalDate validFrom, LocalDate validTo,
                         Status status) {

    /** Lifecycle of a policy as far as claims are concerned. */
    public enum Status {
        ACTIVE, LAPSED, CANCELLED
    }

    /** A policy accepts a claim for an incident on this date only if it is ACTIVE and the date is within its cover. */
    public boolean coversIncidentOn(LocalDate incidentDate) {
        return status == Status.ACTIVE && !incidentDate.isBefore(validFrom) && !incidentDate.isAfter(validTo);
    }
}
