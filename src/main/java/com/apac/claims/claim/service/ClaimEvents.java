package com.apac.claims.claim.service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.shared.event.DomainEvent;

import org.springframework.stereotype.Component;

/**
 * Builds the domain events of a claim. The payload follows the specification: claim number, claim type, status,
 * market code, claimant user id, the assigned officer when there is one, and amounts, currency and reason code only
 * for the events they belong to. A field that has no value is left out, never sent as null. Call it inside the
 * transaction of the state change, because the claim's market and users are loaded lazily.
 */
@Component
public class ClaimEvents {

    public static final String AGGREGATE_TYPE = "Claim";

    public static final String CLAIM_SUBMITTED = "ClaimSubmitted";
    public static final String CLAIM_ASSIGNED = "ClaimAssigned";
    public static final String INFORMATION_REQUESTED = "InformationRequested";
    public static final String INFORMATION_PROVIDED = "InformationProvided";
    public static final String CLAIM_ESCALATED = "ClaimEscalated";
    public static final String CLAIM_APPROVED = "ClaimApproved";
    public static final String CLAIM_REJECTED = "ClaimRejected";
    public static final String CLAIM_SETTLED = "ClaimSettled";

    private final Clock clock;

    public ClaimEvents(Clock clock) {
        this.clock = clock;
    }

    public DomainEvent submitted(Claim claim) {
        Map<String, Object> payload = common(claim);
        payload.put("claimedAmount", claim.getClaimedAmount());
        payload.put("currencyCode", claim.getCurrencyCode());
        return event(CLAIM_SUBMITTED, claim, payload);
    }

    public DomainEvent assigned(Claim claim) {
        return event(CLAIM_ASSIGNED, claim, common(claim));
    }

    public DomainEvent informationRequested(Claim claim) {
        return event(INFORMATION_REQUESTED, claim, common(claim));
    }

    public DomainEvent informationProvided(Claim claim) {
        return event(INFORMATION_PROVIDED, claim, common(claim));
    }

    public DomainEvent escalated(Claim claim) {
        return event(CLAIM_ESCALATED, claim, common(claim));
    }

    public DomainEvent approved(Claim claim) {
        Map<String, Object> payload = common(claim);
        putIfPresent(payload, "approvedAmount", claim.getApprovedAmount());
        payload.put("currencyCode", claim.getCurrencyCode());
        return event(CLAIM_APPROVED, claim, payload);
    }

    public DomainEvent rejected(Claim claim) {
        Map<String, Object> payload = common(claim);
        putIfPresent(payload, "reasonCode", claim.getDecisionReason() == null ? null : claim.getDecisionReason().name());
        return event(CLAIM_REJECTED, claim, payload);
    }

    /** Built here so all eight events live in one place; settlement itself arrives with the integration prompt. */
    public DomainEvent settled(Claim claim) {
        Map<String, Object> payload = common(claim);
        putIfPresent(payload, "settledAmount", claim.getSettledAmount());
        payload.put("currencyCode", claim.getCurrencyCode());
        putIfPresent(payload, "paymentReference", claim.getPaymentReference());
        return event(CLAIM_SETTLED, claim, payload);
    }

    private DomainEvent event(String type, Claim claim, Map<String, Object> payload) {
        return DomainEvent.of(clock, type, AGGREGATE_TYPE, claim.getClaimNumber(), payload);
    }

    /** The fields every event carries; the officer only when the claim has one. */
    private static Map<String, Object> common(Claim claim) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("claimNumber", claim.getClaimNumber());
        payload.put("claimType", claim.getClaimType().name());
        payload.put("status", claim.getStatus().name());
        payload.put("marketCode", claim.getMarket().getCode());
        payload.put("claimantUserId", claim.getClaimant().getUserId());
        if (claim.getAssignedOfficer() != null) {
            payload.put("assignedOfficerUserId", claim.getAssignedOfficer().getUserId());
        }
        return payload;
    }

    private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
