package com.apac.claims.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.support.RepositoryTest;
import com.apac.claims.support.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@RepositoryTest
class ClaimRepositoryTest {

    @Autowired
    private ClaimRepository claims;
    @Autowired
    private UserRepository users;
    @Autowired
    private MarketRepository markets;
    @Autowired
    private TestEntityManager entityManager;

    private Market india;
    private Market singapore;
    private AppUser claimant;
    private AppUser officer;

    @BeforeEach
    void createParents() {
        india = markets.findByCode("IN").orElseThrow();
        singapore = markets.findByCode("SG").orElseThrow();
        claimant = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        officer = users.saveAndFlush(TestData.user(Role.OFFICER, india));
    }

    @Test
    void findByClaimNumberReturnsTheClaim() {
        Claim saved = claims.saveAndFlush(TestData.claim(claimant, india, ClaimType.MOTOR));

        assertThat(claims.findByClaimNumber(saved.getClaimNumber())).get().isEqualTo(saved);
    }

    @Test
    void findByClaimNumberIsEmptyForAnUnknownNumber() {
        assertThat(claims.findByClaimNumber("CLM-00000")).isEmpty();
    }

    @Test
    void versionStartsAtZeroAndIncreasesOnEveryUpdate() {
        Claim claim = claims.saveAndFlush(TestData.claim(claimant, india, ClaimType.MOTOR));
        assertThat(claim.getVersion()).isZero();

        claim.setStatus(ClaimStatus.UNDER_REVIEW);
        claims.saveAndFlush(claim);

        assertThat(claim.getVersion()).isEqualTo(1L);
    }

    @Test
    void aStaleCopyCannotOverwriteANewerVersion() {
        // This is what decides two officers picking up the same claim: exactly one save wins.
        Long id = claims.saveAndFlush(TestData.claim(claimant, india, ClaimType.MOTOR)).getId();
        entityManager.clear();
        Claim stale = claims.findById(id).orElseThrow();
        entityManager.detach(stale);

        Claim fresh = claims.findById(id).orElseThrow();
        fresh.setStatus(ClaimStatus.UNDER_REVIEW);
        fresh.setAssignedOfficer(officer);
        claims.saveAndFlush(fresh);

        stale.setStatus(ClaimStatus.UNDER_REVIEW);
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> claims.saveAndFlush(stale));
    }

    @Test
    void timestampsAreSetOnInsert() {
        Instant before = Instant.now();

        Claim saved = claims.saveAndFlush(TestData.claim(claimant, india, ClaimType.MOTOR));

        assertThat(saved.getCreatedAt()).isBetween(before, Instant.now());
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(saved.getCreatedAt());
    }

    @Test
    void motorDetailsSurviveARoundTrip() {
        Claim claim = TestData.claim(claimant, india, ClaimType.MOTOR);
        claim.setVehicleRegistrationNumber("MH12AB1234");
        claim.setVehicleMake("Hyundai");
        claim.setVehicleModel("i20");
        claim.setVehicleYear(2021);
        claim.setDamageType(DamageType.COLLISION);
        claim.setPoliceReportNumber("FIR-2026-5521");
        Long id = claims.saveAndFlush(claim).getId();
        entityManager.clear();

        Claim found = claims.findById(id).orElseThrow();

        assertThat(found.getVehicleRegistrationNumber()).isEqualTo("MH12AB1234");
        assertThat(found.getVehicleMake()).isEqualTo("Hyundai");
        assertThat(found.getVehicleModel()).isEqualTo("i20");
        assertThat(found.getVehicleYear()).isEqualTo(2021);
        assertThat(found.getDamageType()).isEqualTo(DamageType.COLLISION);
        assertThat(found.getPoliceReportNumber()).isEqualTo("FIR-2026-5521");
        assertThat(found.getPropertyAddress()).isNull();
    }

    @Test
    void propertyDetailsSurviveARoundTrip() {
        Claim claim = TestData.claim(claimant, india, ClaimType.PROPERTY);
        claim.setPropertyAddress("Flat 402, Lakeview Apartments, Powai, Mumbai 400076");
        claim.setPropertyType(PropertyType.RESIDENTIAL);
        claim.setPerilType(PerilType.FLOOD);
        Long id = claims.saveAndFlush(claim).getId();
        entityManager.clear();

        Claim found = claims.findById(id).orElseThrow();

        assertThat(found.getPropertyAddress()).startsWith("Flat 402");
        assertThat(found.getPropertyType()).isEqualTo(PropertyType.RESIDENTIAL);
        assertThat(found.getPerilType()).isEqualTo(PerilType.FLOOD);
        assertThat(found.getVehicleYear()).isNull();
    }

    @Test
    void decisionAndSettlementFieldsSurviveARoundTrip() {
        Claim claim = TestData.claim(claimant, india, ClaimType.MOTOR);
        claim.setAssignedOfficer(officer);
        claim.setAssignedAt(Instant.parse("2026-10-01T08:00:00Z"));
        claim.setDecisionType(DecisionType.APPROVED);
        claim.setDecisionRemarks("Approved as assessed.");
        claim.setDecidedBy(officer);
        claim.setDecidedAt(Instant.parse("2026-10-02T09:30:00Z"));
        claim.setApprovedAmount(new BigDecimal("3000.00"));
        claim.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        claim.setPaymentReference("PAY-0123456789AB");
        claim.setSettledAmount(new BigDecimal("3000.00"));
        claim.setSettledAt(Instant.parse("2026-10-03T10:00:00Z"));
        Long id = claims.saveAndFlush(claim).getId();
        entityManager.clear();

        Claim found = claims.findById(id).orElseThrow();

        assertThat(found.getAssignedOfficer().getUserId()).isEqualTo(officer.getUserId());
        assertThat(found.getAssignedAt()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
        assertThat(found.getDecisionType()).isEqualTo(DecisionType.APPROVED);
        assertThat(found.getDecidedBy().getUserId()).isEqualTo(officer.getUserId());
        assertThat(found.getDecidedAt()).isEqualTo(Instant.parse("2026-10-02T09:30:00Z"));
        assertThat(found.getApprovedAmount()).isEqualTo(new BigDecimal("3000.00"));
        assertThat(found.getPaymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(found.getPaymentReference()).isEqualTo("PAY-0123456789AB");
        assertThat(found.getSettledAmount()).isEqualTo(new BigDecimal("3000.00"));
        assertThat(found.getSettledAt()).isEqualTo(Instant.parse("2026-10-03T10:00:00Z"));
    }

    // ---- duplicate-claim existence query -----------------------------------------------------------------

    @Test
    void duplicateCheckFindsAnOpenClaimForTheSamePolicyDateAndType() {
        Claim existing = claims.saveAndFlush(TestData.claim(claimant, india, ClaimType.MOTOR));

        assertThat(claims.existsByPolicyNumberAndIncidentDateAndClaimTypeAndStatusNot(existing.getPolicyNumber(),
                existing.getIncidentDate(), ClaimType.MOTOR, ClaimStatus.REJECTED)).isTrue();
    }

    @Test
    void duplicateCheckIgnoresARejectedClaim() {
        Claim rejected = TestData.claim(claimant, india, ClaimType.MOTOR);
        rejected.setStatus(ClaimStatus.REJECTED);
        claims.saveAndFlush(rejected);

        assertThat(claims.existsByPolicyNumberAndIncidentDateAndClaimTypeAndStatusNot(rejected.getPolicyNumber(),
                rejected.getIncidentDate(), ClaimType.MOTOR, ClaimStatus.REJECTED)).isFalse();
    }

    @Test
    void duplicateCheckStillFindsTheOpenClaimWhenARejectedOneExistsToo() {
        Claim rejected = TestData.claim(claimant, india, ClaimType.MOTOR);
        rejected.setStatus(ClaimStatus.REJECTED);
        claims.saveAndFlush(rejected);
        Claim open = TestData.claim(claimant, india, ClaimType.MOTOR);
        open.setPolicyNumber(rejected.getPolicyNumber());
        open.setIncidentDate(rejected.getIncidentDate());
        claims.saveAndFlush(open);

        assertThat(claims.existsByPolicyNumberAndIncidentDateAndClaimTypeAndStatusNot(rejected.getPolicyNumber(),
                rejected.getIncidentDate(), ClaimType.MOTOR, ClaimStatus.REJECTED)).isTrue();
    }

    @Test
    void duplicateCheckIsFalseForADifferentDateTypeOrPolicy() {
        Claim existing = claims.saveAndFlush(TestData.claim(claimant, india, ClaimType.MOTOR));
        String policy = existing.getPolicyNumber();
        LocalDate date = existing.getIncidentDate();

        assertThat(claims.existsByPolicyNumberAndIncidentDateAndClaimTypeAndStatusNot(policy, date.minusDays(1),
                ClaimType.MOTOR, ClaimStatus.REJECTED)).isFalse();
        assertThat(claims.existsByPolicyNumberAndIncidentDateAndClaimTypeAndStatusNot(policy, date,
                ClaimType.PROPERTY, ClaimStatus.REJECTED)).isFalse();
        assertThat(claims.existsByPolicyNumberAndIncidentDateAndClaimTypeAndStatusNot("OTHER-POLICY", date,
                ClaimType.MOTOR, ClaimStatus.REJECTED)).isFalse();
    }

    // ---- specification support ---------------------------------------------------------------------------

    @Test
    void claimsCanBeFilteredByMarketAndStatusWithASpecification() {
        AppUser singaporeClaimant = users.saveAndFlush(TestData.user(Role.CLAIMANT, singapore));
        Claim matching = claims.save(TestData.claim(claimant, india, ClaimType.MOTOR));
        Claim wrongStatus = TestData.claim(claimant, india, ClaimType.MOTOR);
        wrongStatus.setStatus(ClaimStatus.APPROVED);
        claims.save(wrongStatus);
        claims.save(TestData.claim(singaporeClaimant, singapore, ClaimType.MOTOR));
        claims.flush();
        Specification<Claim> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("market"), india),
                cb.equal(root.get("status"), ClaimStatus.SUBMITTED));

        Page<Claim> page = claims.findAll(spec, PageRequest.of(0, 10, Sort.by("id")));

        assertThat(page.getContent()).containsExactly(matching);
    }
}
