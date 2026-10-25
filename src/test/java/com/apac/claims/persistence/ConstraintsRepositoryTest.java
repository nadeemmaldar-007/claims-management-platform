package com.apac.claims.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.IdempotencyKey;
import com.apac.claims.claim.domain.IdempotencyKeyRepository;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.integration.domain.PaymentInstruction;
import com.apac.claims.integration.domain.PaymentInstructionRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.notification.domain.Notification;
import com.apac.claims.notification.domain.NotificationRepository;
import com.apac.claims.policy.domain.Policy;
import com.apac.claims.policy.domain.PolicyRepository;
import com.apac.claims.policy.domain.ProductType;
import com.apac.claims.support.RepositoryTest;
import com.apac.claims.support.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every constraint the specification names must be enforced by the database itself, not only by application
 * code. Each test provokes one violation and flushes, so the database gets to answer.
 */
@RepositoryTest
class ConstraintsRepositoryTest {

    @Autowired
    private MarketRepository markets;
    @Autowired
    private UserRepository users;
    @Autowired
    private PolicyRepository policies;
    @Autowired
    private ClaimRepository claims;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeys;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private PaymentInstructionRepository payments;
    @Autowired
    private JdbcTemplate jdbc;

    private Market india;

    @BeforeEach
    void loadMarket() {
        india = markets.findByCode("IN").orElseThrow();
    }

    @Test
    void duplicateMarketCodeIsRejected() {
        Market duplicate = new Market("IN", "Duplicate", "INR", new BigDecimal("1.00"), true);

        assertThrows(DataIntegrityViolationException.class, () -> markets.saveAndFlush(duplicate));
    }

    @Test
    void duplicateUserIdIsRejected() {
        AppUser first = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        AppUser second = TestData.user(Role.CLAIMANT, india);
        second.setUserId(first.getUserId());

        assertThrows(DataIntegrityViolationException.class, () -> users.saveAndFlush(second));
    }

    @Test
    void duplicateEmailIsRejected() {
        AppUser first = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        AppUser second = TestData.user(Role.CLAIMANT, india);
        second.setEmail(first.getEmail());

        assertThrows(DataIntegrityViolationException.class, () -> users.saveAndFlush(second));
    }

    @Test
    void anOfficerWithoutAMarketViolatesTheCheckConstraint() {
        AppUser officer = TestData.user(Role.OFFICER, null);

        assertThrows(DataIntegrityViolationException.class, () -> users.saveAndFlush(officer));
    }

    @Test
    void aClaimantWithoutAMarketViolatesTheCheckConstraint() {
        AppUser claimant = TestData.user(Role.CLAIMANT, null);

        assertThrows(DataIntegrityViolationException.class, () -> users.saveAndFlush(claimant));
    }

    @Test
    void aManagerMayHaveNoMarket() {
        AppUser manager = TestData.user(Role.MANAGER, null);

        assertThatCode(() -> users.saveAndFlush(manager)).doesNotThrowAnyException();
    }

    @Test
    void duplicatePolicyNumberIsRejected() {
        AppUser holder = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        Policy first = policies.saveAndFlush(TestData.policy(holder, india, ProductType.MOTOR));
        Policy second = TestData.policy(holder, india, ProductType.MOTOR);
        second.setPolicyNumber(first.getPolicyNumber());

        assertThrows(DataIntegrityViolationException.class, () -> policies.saveAndFlush(second));
    }

    @Test
    void duplicateClaimNumberIsRejected() {
        AppUser claimant = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        Claim first = claims.saveAndFlush(TestData.claim(claimant, india, ClaimType.MOTOR));
        Claim second = TestData.claim(claimant, india, ClaimType.MOTOR);
        second.setClaimNumber(first.getClaimNumber());

        assertThrows(DataIntegrityViolationException.class, () -> claims.saveAndFlush(second));
    }

    @Test
    void duplicateIdempotencyKeyForTheSameUserIsRejected() {
        AppUser user = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        idempotencyKeys.saveAndFlush(new IdempotencyKey(user, "key-12345678", "CLM-T00000001"));

        assertThrows(DataIntegrityViolationException.class,
                () -> idempotencyKeys.saveAndFlush(new IdempotencyKey(user, "key-12345678", "CLM-T00000002")));
    }

    @Test
    void theSameIdempotencyKeyIsAllowedForADifferentUser() {
        AppUser one = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        AppUser two = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        idempotencyKeys.saveAndFlush(new IdempotencyKey(one, "key-12345678", "CLM-T00000001"));

        assertThatCode(() -> idempotencyKeys.saveAndFlush(new IdempotencyKey(two, "key-12345678", "CLM-T00000002")))
                .doesNotThrowAnyException();
    }

    @Test
    void duplicateSourceEventAndRecipientIsRejected() {
        AppUser recipient = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        String eventId = TestData.eventId();
        notifications.saveAndFlush(TestData.notification(recipient, eventId));

        assertThrows(DataIntegrityViolationException.class,
                () -> notifications.saveAndFlush(TestData.notification(recipient, eventId)));
    }

    @Test
    void theSameEventMayNotifyDifferentRecipients() {
        AppUser one = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        AppUser two = users.saveAndFlush(TestData.user(Role.OFFICER, india));
        String eventId = TestData.eventId();
        notifications.saveAndFlush(TestData.notification(one, eventId));

        assertThatCode(() -> notifications.saveAndFlush(TestData.notification(two, eventId)))
                .doesNotThrowAnyException();
    }

    @Test
    void aSecondPaymentInstructionForTheSameClaimIsRejected() {
        payments.saveAndFlush(TestData.paymentInstruction("CLM-T00000009"));

        assertThrows(DataIntegrityViolationException.class,
                () -> payments.saveAndFlush(TestData.paymentInstruction("CLM-T00000009")));
    }

    @Test
    void aDuplicatePaymentReferenceIsRejected() {
        PaymentInstruction first = payments.saveAndFlush(TestData.paymentInstruction("CLM-T00000010"));
        PaymentInstruction second = new PaymentInstruction("CLM-T00000011", first.getAmount(), "INR",
                first.getPaymentMethod(), first.getReference(), first.getStatus());

        assertThrows(DataIntegrityViolationException.class, () -> payments.saveAndFlush(second));
    }

    @Test
    void foreignKeysAreEnforced() {
        // A native insert, because JPA would refuse to build an assessment without a persistent claim.
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO claim_assessments (claim_id, assessed_by_id, assessed_loss_amount, deductible_applied, "
                        + "recommended_payout, coverage_confirmed, recommendation, created_at) "
                        + "VALUES (999999999, 999999999, 1.00, 1.00, 0.00, TRUE, 'APPROVE', CURRENT_TIMESTAMP)"));
    }

    @Test
    void aClaimMayReferToAPolicyNumberThatDoesNotExistBecauseModulesOwnTheirTables() {
        AppUser claimant = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        Claim claim = TestData.claim(claimant, india, ClaimType.MOTOR);
        claim.setPolicyNumber("NO-SUCH-POLICY");

        Claim saved = claims.saveAndFlush(claim);

        assertThat(saved.getPolicyNumber()).isEqualTo("NO-SUCH-POLICY");
    }
}
