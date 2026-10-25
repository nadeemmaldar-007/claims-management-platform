package com.apac.claims.policy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

@RepositoryTest
class PolicyRepositoryTest {

    @Autowired
    private PolicyRepository policies;
    @Autowired
    private UserRepository users;
    @Autowired
    private MarketRepository markets;
    @Autowired
    private TestEntityManager entityManager;

    private Market japan;
    private AppUser holder;

    @BeforeEach
    void createHolder() {
        japan = markets.findByCode("JP").orElseThrow();
        holder = users.saveAndFlush(TestData.user(Role.CLAIMANT, japan));
    }

    @Test
    void findByPolicyNumberReturnsThePolicyWithItsHolderAndMarket() {
        Policy saved = policies.saveAndFlush(TestData.policy(holder, japan, ProductType.MOTOR));
        entityManager.clear();

        Policy found = policies.findByPolicyNumber(saved.getPolicyNumber()).orElseThrow();

        assertThat(found.getHolder().getUserId()).isEqualTo(holder.getUserId());
        assertThat(found.getMarket().getCode()).isEqualTo("JP");
        assertThat(found.getProductType()).isEqualTo(ProductType.MOTOR);
    }

    @Test
    void findByPolicyNumberIsEmptyForAnUnknownPolicy() {
        assertThat(policies.findByPolicyNumber("NO-SUCH-POLICY")).isEmpty();
    }

    @Test
    void findByHolderReturnsOnlyThatHoldersPolicies() {
        Policy motor = policies.save(TestData.policy(holder, japan, ProductType.MOTOR));
        Policy property = policies.save(TestData.policy(holder, japan, ProductType.PROPERTY));
        AppUser other = users.saveAndFlush(TestData.user(Role.CLAIMANT, japan));
        policies.saveAndFlush(TestData.policy(other, japan, ProductType.MOTOR));

        assertThat(policies.findByHolder(holder)).containsExactlyInAnyOrder(motor, property);
    }

    @Test
    void moneyAndDatesSurviveARoundTripWithTheirScale() {
        Policy policy = TestData.policy(holder, japan, ProductType.PROPERTY);
        policy.setSumInsured(new BigDecimal("10000000.00"));
        policy.setDeductible(new BigDecimal("10000.00"));
        policy.setValidFrom(LocalDate.of(2025, 3, 1));
        policy.setValidTo(LocalDate.of(2026, 2, 28));
        Policy saved = policies.saveAndFlush(policy);
        entityManager.clear();

        Policy found = policies.findById(saved.getId()).orElseThrow();

        assertThat(found.getSumInsured()).isEqualTo(new BigDecimal("10000000.00"));
        assertThat(found.getDeductible()).isEqualTo(new BigDecimal("10000.00"));
        assertThat(found.getValidFrom()).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(found.getValidTo()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void timestampsAreSetOnInsertAndUpdatedAtNeverFallsBehindCreatedAt() {
        Policy saved = policies.saveAndFlush(TestData.policy(holder, japan, ProductType.MOTOR));
        saved.setStatus(PolicyStatus.LAPSED);
        policies.saveAndFlush(saved);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(saved.getCreatedAt());
    }
}
