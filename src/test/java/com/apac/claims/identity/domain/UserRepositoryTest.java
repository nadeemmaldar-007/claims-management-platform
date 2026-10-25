package com.apac.claims.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

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

@RepositoryTest
class UserRepositoryTest {

    @Autowired
    private UserRepository users;
    @Autowired
    private MarketRepository markets;
    @Autowired
    private TestEntityManager entityManager;

    private Market india;
    private Market singapore;

    @BeforeEach
    void loadMarkets() {
        india = markets.findByCode("IN").orElseThrow();
        singapore = markets.findByCode("SG").orElseThrow();
    }

    @Test
    void findByUserIdReturnsTheAccount() {
        AppUser saved = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));

        assertThat(users.findByUserId(saved.getUserId())).get().isEqualTo(saved);
    }

    @Test
    void findByUserIdIsEmptyForAnUnknownUser() {
        assertThat(users.findByUserId("nobody.here")).isEmpty();
    }

    @Test
    void existsChecksCoverUserIdAndEmail() {
        AppUser saved = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));

        assertThat(users.existsByUserId(saved.getUserId())).isTrue();
        assertThat(users.existsByEmail(saved.getEmail())).isTrue();
        assertThat(users.existsByUserId("nobody.here")).isFalse();
        assertThat(users.existsByEmail("nobody@test.example")).isFalse();
    }

    @Test
    void findActiveOfficersOfAMarketExcludesInactiveOtherMarketsAndOtherRoles() {
        AppUser activeIndia = users.save(TestData.user(Role.OFFICER, india));
        AppUser inactiveIndia = TestData.user(Role.OFFICER, india);
        inactiveIndia.setActive(false);
        users.save(inactiveIndia);
        users.save(TestData.user(Role.OFFICER, singapore));
        users.save(TestData.user(Role.CLAIMANT, india));
        users.flush();

        List<AppUser> result = users.findByRoleAndMarketAndActiveTrue(Role.OFFICER, india);

        assertThat(result).containsExactly(activeIndia);
    }

    @Test
    void findActiveUsersByRoleSpansMarketsAndSkipsInactiveAccounts() {
        AppUser managerOne = users.save(TestData.user(Role.MANAGER, null));
        AppUser managerTwo = users.save(TestData.user(Role.MANAGER, null));
        AppUser inactiveManager = TestData.user(Role.MANAGER, null);
        inactiveManager.setActive(false);
        users.save(inactiveManager);
        users.save(TestData.user(Role.OFFICER, india));
        users.flush();

        assertThat(users.findByRoleAndActiveTrue(Role.MANAGER)).containsExactlyInAnyOrder(managerOne, managerTwo);
    }

    @Test
    void pagedListCanBeFilteredWithASpecification() {
        for (int i = 0; i < 3; i++) {
            users.save(TestData.user(Role.OFFICER, india));
        }
        users.save(TestData.user(Role.CLAIMANT, india));
        users.flush();
        Specification<AppUser> officersOnly = (root, query, cb) -> cb.equal(root.get("role"), Role.OFFICER);

        Page<AppUser> page = users.findAll(officersOnly, PageRequest.of(0, 2, Sort.by("userId")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(2)
                .allSatisfy(user -> assertThat(user.getRole()).isEqualTo(Role.OFFICER));
    }

    @Test
    void createdAtIsSetWhenTheAccountIsInserted() {
        Instant before = Instant.now();

        AppUser saved = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));

        assertThat(saved.getCreatedAt()).isBetween(before, Instant.now());
    }

    @Test
    void theMarketAssociationIsLoadedLazilyAndReadableInsideTheTransaction() {
        AppUser saved = users.saveAndFlush(TestData.user(Role.CLAIMANT, singapore));
        entityManager.clear();

        AppUser reloaded = users.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getMarket().getCode()).isEqualTo("SG");
    }

    @Test
    void aManagerIsStoredWithoutAMarket() {
        AppUser saved = users.saveAndFlush(TestData.user(Role.MANAGER, null));
        entityManager.clear();

        assertThat(users.findById(saved.getId()).orElseThrow().getMarket()).isNull();
    }
}
