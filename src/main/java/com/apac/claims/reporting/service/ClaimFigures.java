package com.apac.claims.reporting.service;

import java.math.BigDecimal;
import java.time.Instant;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.DecisionType;

/**
 * The figures of one claim that the reports need, copied out of the entity inside the transaction. The calculators
 * work on these plain values, so every number of a report can be tested with hand-made data and a fixed clock, without a
 * database and without lazy loading.
 *
 * @param officerUserId user id of the assigned officer, or null while nobody has the claim
 * @param assignedAt when the claim was last assigned (a reassignment moves it), or null
 * @param decidedByUserId user id of whoever approved or rejected the claim, or null
 */
public record ClaimFigures(String marketCode, String currencyCode, ClaimType claimType, ClaimStatus status,
                           Instant createdAt, BigDecimal claimedAmount, BigDecimal approvedAmount,
                           BigDecimal settledAmount, String officerUserId, Instant assignedAt, Instant decidedAt,
                           String decidedByUserId, DecisionType decisionType, Instant settledAt) {

    public boolean open() {
        return status.isOpen();
    }
}
