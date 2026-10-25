package com.apac.claims.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.apac.claims.support.RepositoryTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * Covers the locking query that the claim number generator will use. Real contention between two threads is not
 * tested here; the lock is only exercised for correctness of the query and of the increment inside a transaction.
 */
@RepositoryTest
class ClaimCounterRepositoryTest {

    @Autowired
    private ClaimCounterRepository counters;
    @Autowired
    private TestEntityManager entityManager;

    @ParameterizedTest
    @EnumSource(ClaimType.class)
    void findForUpdateReturnsTheCounterOfThatType(ClaimType type) {
        ClaimCounter counter = counters.findForUpdate(type).orElseThrow();

        assertThat(counter.getClaimType()).isEqualTo(type);
        assertThat(counter.getNextValue()).isEqualTo(10001L);
    }

    @Test
    void anIncrementMadeUnderTheLockIsPersistedWithinTheTransaction() {
        ClaimCounter counter = counters.findForUpdate(ClaimType.MOTOR).orElseThrow();
        counter.setNextValue(counter.getNextValue() + 1);
        counters.flush();
        entityManager.clear();

        assertThat(counters.findById(ClaimType.MOTOR).orElseThrow().getNextValue()).isEqualTo(10002L);
    }

    @Test
    void theTwoCountersAreIndependent() {
        ClaimCounter motor = counters.findForUpdate(ClaimType.MOTOR).orElseThrow();
        motor.setNextValue(20000L);
        counters.flush();
        entityManager.clear();

        assertThat(counters.findForUpdate(ClaimType.PROPERTY).orElseThrow().getNextValue()).isEqualTo(10001L);
    }
}
