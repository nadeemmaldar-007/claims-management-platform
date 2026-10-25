package com.apac.claims.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import com.apac.claims.claim.domain.AssessmentRecommendation;
import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimAssessment;
import com.apac.claims.claim.domain.ClaimAssessmentRepository;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimTimelineEntry;
import com.apac.claims.claim.domain.ClaimTimelineRepository;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.DamageType;
import com.apac.claims.claim.domain.DecisionType;
import com.apac.claims.claim.domain.InformationRequest;
import com.apac.claims.claim.domain.InformationRequestRepository;
import com.apac.claims.claim.domain.InformationRequestStatus;
import com.apac.claims.claim.domain.PaymentMethod;
import com.apac.claims.claim.domain.PerilType;
import com.apac.claims.claim.domain.PropertyType;
import com.apac.claims.claim.domain.RejectionReason;
import com.apac.claims.claim.domain.TimelineEntryType;
import com.apac.claims.claim.domain.TimelineVisibility;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.integration.domain.OutboxEvent;
import com.apac.claims.integration.domain.OutboxEventRepository;
import com.apac.claims.integration.domain.OutboxStatus;
import com.apac.claims.integration.domain.PaymentInstruction;
import com.apac.claims.integration.domain.PaymentInstructionRepository;
import com.apac.claims.integration.domain.PaymentStatus;
import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.notification.domain.Notification;
import com.apac.claims.notification.domain.NotificationRepository;
import com.apac.claims.notification.domain.NotificationType;
import com.apac.claims.policy.domain.Policy;
import com.apac.claims.policy.domain.PolicyRepository;
import com.apac.claims.policy.domain.PolicyStatus;
import com.apac.claims.policy.domain.ProductType;
import com.apac.claims.support.RepositoryTest;
import com.apac.claims.support.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Enum drift guard. Each CHECK constraint lists the enum constants as strings; if a Java constant is added or
 * renamed without a migration, the matching test fails because the database refuses the row. A second group
 * proves the constraints really reject values outside the list.
 */
@RepositoryTest
class EnumDriftRepositoryTest {

    @Autowired
    private MarketRepository markets;
    @Autowired
    private UserRepository users;
    @Autowired
    private PolicyRepository policies;
    @Autowired
    private ClaimRepository claims;
    @Autowired
    private ClaimAssessmentRepository assessments;
    @Autowired
    private InformationRequestRepository informationRequests;
    @Autowired
    private ClaimTimelineRepository timeline;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private PaymentInstructionRepository payments;
    @Autowired
    private JdbcTemplate jdbc;

    private Market india;
    private AppUser claimant;
    private AppUser officer;

    @BeforeEach
    void createParents() {
        india = markets.findByCode("IN").orElseThrow();
        claimant = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        officer = users.saveAndFlush(TestData.user(Role.OFFICER, india));
    }

    private Claim newClaim() {
        return TestData.claim(claimant, india, ClaimType.MOTOR);
    }

    // ---- the six enums the specification names explicitly -------------------------------------------------

    @ParameterizedTest
    @EnumSource(ClaimStatus.class)
    void claimsAcceptEveryClaimStatus(ClaimStatus status) {
        Claim claim = newClaim();
        claim.setStatus(status);

        assertThatCode(() -> claims.saveAndFlush(claim)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(TimelineEntryType.class)
    void timelineAcceptsEveryEntryType(TimelineEntryType type) {
        Claim claim = claims.saveAndFlush(newClaim());

        assertThatCode(() -> timeline.saveAndFlush(TestData.timelineEntry(claim, type, TimelineVisibility.PUBLIC)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    void notificationsAcceptEveryNotificationType(NotificationType type) {
        Notification notification = new Notification(claimant, "CLM-T00000001", type, "title", "message",
                TestData.eventId());

        assertThatCode(() -> notifications.saveAndFlush(notification)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(RejectionReason.class)
    void claimsAcceptEveryRejectionReason(RejectionReason reason) {
        Claim claim = newClaim();
        claim.setDecisionType(DecisionType.REJECTED);
        claim.setDecisionReason(reason);

        assertThatCode(() -> claims.saveAndFlush(claim)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(OutboxStatus.class)
    void outboxAcceptsEveryOutboxStatus(OutboxStatus status) {
        OutboxEvent event = TestData.outboxEvent(Instant.now());
        event.setStatus(status);

        assertThatCode(() -> outbox.saveAndFlush(event)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void paymentsAcceptEveryPaymentStatus(PaymentStatus status) {
        PaymentInstruction instruction = TestData.paymentInstruction("CLM-T" + TestData.unique().substring(0, 8));
        instruction.setStatus(status);

        assertThatCode(() -> payments.saveAndFlush(instruction)).doesNotThrowAnyException();
    }

    // ---- the remaining enums that have a CHECK constraint -------------------------------------------------

    @ParameterizedTest
    @EnumSource(ClaimType.class)
    void claimsAcceptEveryClaimType(ClaimType type) {
        assertThatCode(() -> claims.saveAndFlush(TestData.claim(claimant, india, type))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(DamageType.class)
    void claimsAcceptEveryDamageType(DamageType type) {
        Claim claim = newClaim();
        claim.setDamageType(type);

        assertThatCode(() -> claims.saveAndFlush(claim)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(PropertyType.class)
    void claimsAcceptEveryPropertyType(PropertyType type) {
        Claim claim = TestData.claim(claimant, india, ClaimType.PROPERTY);
        claim.setPropertyType(type);

        assertThatCode(() -> claims.saveAndFlush(claim)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(PerilType.class)
    void claimsAcceptEveryPerilType(PerilType type) {
        Claim claim = TestData.claim(claimant, india, ClaimType.PROPERTY);
        claim.setPerilType(type);

        assertThatCode(() -> claims.saveAndFlush(claim)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(DecisionType.class)
    void claimsAcceptEveryDecisionType(DecisionType type) {
        Claim claim = newClaim();
        claim.setDecisionType(type);

        assertThatCode(() -> claims.saveAndFlush(claim)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    void claimsAcceptEveryPaymentMethod(PaymentMethod method) {
        Claim claim = newClaim();
        claim.setPaymentMethod(method);

        assertThatCode(() -> claims.saveAndFlush(claim)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(AssessmentRecommendation.class)
    void assessmentsAcceptEveryRecommendation(AssessmentRecommendation recommendation) {
        Claim claim = claims.saveAndFlush(newClaim());
        ClaimAssessment assessment = TestData.assessment(claim, officer);
        assessment.setRecommendation(recommendation);

        assertThatCode(() -> assessments.saveAndFlush(assessment)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(InformationRequestStatus.class)
    void informationRequestsAcceptEveryStatus(InformationRequestStatus status) {
        Claim claim = claims.saveAndFlush(newClaim());
        InformationRequest request = TestData.informationRequest(claim, officer);
        request.setStatus(status);

        assertThatCode(() -> informationRequests.saveAndFlush(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(TimelineVisibility.class)
    void timelineAcceptsEveryVisibility(TimelineVisibility visibility) {
        Claim claim = claims.saveAndFlush(newClaim());

        assertThatCode(() -> timeline.saveAndFlush(
                TestData.timelineEntry(claim, TimelineEntryType.SUBMITTED, visibility))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(ClaimStatus.class)
    void timelineAcceptsEveryClaimStatusAsFromAndToStatus(ClaimStatus status) {
        Claim claim = claims.saveAndFlush(newClaim());
        ClaimTimelineEntry entry = new ClaimTimelineEntry(claim, TimelineEntryType.ASSIGNED, status, status,
                "actor.user", "message", TimelineVisibility.PUBLIC);

        assertThatCode(() -> timeline.saveAndFlush(entry)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(ProductType.class)
    void policiesAcceptEveryProductType(ProductType type) {
        assertThatCode(() -> policies.saveAndFlush(TestData.policy(claimant, india, type)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(PolicyStatus.class)
    void policiesAcceptEveryPolicyStatus(PolicyStatus status) {
        Policy policy = TestData.policy(claimant, india, ProductType.MOTOR);
        policy.setStatus(status);

        assertThatCode(() -> policies.saveAndFlush(policy)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void usersAcceptEveryRole(Role role) {
        // A manager needs no market, the other roles do; giving every role a market satisfies both rules.
        assertThatCode(() -> users.saveAndFlush(TestData.user(role, india))).doesNotThrowAnyException();
    }

    // ---- values outside the list must be refused by the database -----------------------------------------

    private static final String INSERT_CLAIM = "INSERT INTO claims (claim_number, claim_type, status, policy_number, "
            + "policy_sum_insured, policy_deductible, claimant_id, market_id, currency_code, claimed_amount, "
            + "incident_date, incident_location, description, version, created_at, updated_at) "
            + "VALUES (?, 'MOTOR', ?, 'P-1', 1000.00, 10.00, ?, ?, 'INR', 500.00, DATE '2026-01-01', 'location', "
            + "'description text', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";

    @Test
    void aClaimStatusOutsideTheListIsRefused() {
        // The control insert uses a valid value, so the failure below can only come from the CHECK constraint.
        jdbc.update(INSERT_CLAIM, "CLM-T00000101", "SUBMITTED", claimant.getId(), india.getId());

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(INSERT_CLAIM, "CLM-T00000102", "BOGUS", claimant.getId(), india.getId()));
    }

    @Test
    void anOutboxStatusOutsideTheListIsRefused() {
        String sql = "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, occurred_at, "
                + "status, attempts, next_attempt_at) VALUES (?, 'Claim', 'CLM-T00000001', 'ClaimSubmitted', '{}', "
                + "CURRENT_TIMESTAMP, ?, 0, CURRENT_TIMESTAMP)";
        jdbc.update(sql, TestData.eventId(), "PENDING");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, TestData.eventId(), "BOGUS"));
    }

    @Test
    void aNotificationTypeOutsideTheListIsRefused() {
        String sql = "INSERT INTO notifications (recipient_id, claim_number, notification_type, title, message, "
                + "read_flag, source_event_id, created_at) VALUES (?, 'CLM-T00000001', ?, 'title', 'message', FALSE, "
                + "?, CURRENT_TIMESTAMP)";
        jdbc.update(sql, claimant.getId(), "CLAIM_SUBMITTED", TestData.eventId());

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(sql, claimant.getId(), "BOGUS", TestData.eventId()));
    }

    @Test
    void aPaymentStatusOutsideTheListIsRefused() {
        String sql = "INSERT INTO payment_instructions (claim_number, amount, currency_code, payment_method, "
                + "reference, status, created_at) VALUES (?, 10.00, 'INR', 'CHEQUE', ?, ?, CURRENT_TIMESTAMP)";
        jdbc.update(sql, "CLM-T00000201", "PAY-ONE", "INITIATED");

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(sql, "CLM-T00000202", "PAY-TWO", "BOGUS"));
    }

    @Test
    void aTimelineVisibilityOutsideTheListIsRefused() {
        Claim claim = claims.saveAndFlush(newClaim());
        String sql = "INSERT INTO claim_timeline_entries (claim_id, entry_type, actor_user_id, message, visibility, "
                + "occurred_at) VALUES (?, 'SUBMITTED', 'actor.user', 'message', ?, CURRENT_TIMESTAMP)";
        jdbc.update(sql, claim.getId(), "PUBLIC");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, claim.getId(), "BOGUS"));
    }
}
