package com.apac.claims.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimAssessment;
import com.apac.claims.claim.domain.AssessmentRecommendation;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimTimelineEntry;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.InformationRequest;
import com.apac.claims.claim.domain.PaymentMethod;
import com.apac.claims.claim.domain.TimelineEntryType;
import com.apac.claims.claim.domain.TimelineVisibility;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.integration.domain.OutboxEvent;
import com.apac.claims.integration.domain.PaymentInstruction;
import com.apac.claims.integration.domain.PaymentStatus;
import com.apac.claims.market.domain.Market;
import com.apac.claims.notification.domain.Notification;
import com.apac.claims.notification.domain.NotificationType;
import com.apac.claims.policy.domain.Policy;
import com.apac.claims.policy.domain.PolicyStatus;
import com.apac.claims.policy.domain.ProductType;

/**
 * Builders for valid, unsaved entities with unique keys, so every test creates its own data and never depends
 * on another test's rows. Callers set the fields they care about and save through a repository.
 */
public final class TestData {

    private TestData() {
    }

    /** Ten random hexadecimal characters; short enough to fit every key column. */
    public static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    public static String eventId() {
        return UUID.randomUUID().toString();
    }

    public static AppUser user(Role role, Market market) {
        String userId = "t" + unique();
        return new AppUser(userId, "hash-" + unique(), "Test " + role, userId + "@test.example", role, market);
    }

    public static Policy policy(AppUser holder, Market market, ProductType productType) {
        return new Policy("T-" + unique(), market, productType, holder, "Insured item", new BigDecimal("100000.00"),
                new BigDecimal("1000.00"), LocalDate.now().minusYears(1), LocalDate.now().plusYears(1),
                PolicyStatus.ACTIVE);
    }

    public static Claim claim(AppUser claimant, Market market, ClaimType claimType) {
        return new Claim("CLM-T" + unique().substring(0, 8), claimType, ClaimStatus.SUBMITTED, "P-" + unique(),
                new BigDecimal("100000.00"), new BigDecimal("1000.00"), claimant, market, market.getCurrencyCode(),
                new BigDecimal("5000.00"), LocalDate.now().minusDays(3), "Test location",
                "A test claim description");
    }

    public static ClaimAssessment assessment(Claim claim, AppUser assessor) {
        return new ClaimAssessment(claim, assessor, new BigDecimal("4000.00"), new BigDecimal("1000.00"),
                new BigDecimal("3000.00"), true, AssessmentRecommendation.APPROVE, "Test notes");
    }

    public static InformationRequest informationRequest(Claim claim, AppUser requester) {
        return new InformationRequest(claim, requester, "Please send the repair estimate.",
                Instant.now().plusSeconds(7 * 86_400L));
    }

    public static ClaimTimelineEntry timelineEntry(Claim claim, TimelineEntryType type,
                                                   TimelineVisibility visibility) {
        return new ClaimTimelineEntry(claim, type, null, ClaimStatus.SUBMITTED, "actor.user", "Test entry",
                visibility);
    }

    public static Notification notification(AppUser recipient, String sourceEventId) {
        return new Notification(recipient, "CLM-T" + unique().substring(0, 8), NotificationType.CLAIM_SUBMITTED,
                "Test title", "Test message", sourceEventId);
    }

    public static OutboxEvent outboxEvent(Instant occurredAt) {
        return new OutboxEvent(eventId(), "Claim", "CLM-T" + unique().substring(0, 8), "ClaimSubmitted",
                "{\"test\":true}", occurredAt);
    }

    public static PaymentInstruction paymentInstruction(String claimNumber) {
        return new PaymentInstruction(claimNumber, new BigDecimal("3000.00"), "INR", PaymentMethod.BANK_TRANSFER,
                "PAY-" + unique().toUpperCase(), PaymentStatus.INITIATED);
    }
}
