package com.apac.claims.claim.service;

import com.apac.claims.claim.domain.ClaimCounter;
import com.apac.claims.claim.domain.ClaimCounterRepository;
import com.apac.claims.claim.domain.ClaimType;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out claim numbers: {@code CLM-<n>} for motor and {@code CLP-<n>} for property, each type counting up from
 * its own counter. The counter row is read under a pessimistic write lock and incremented in the same transaction
 * that inserts the claim ({@code MANDATORY}: there must already be one), so two submissions never get the same
 * number and a rollback leaves no gap. The lock is held until that transaction ends, so callers generate the
 * number after all validation and keep the rest of the transaction short.
 */
@Component
public class ClaimNumberGenerator {

    private final ClaimCounterRepository counters;

    public ClaimNumberGenerator(ClaimCounterRepository counters) {
        this.counters = counters;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(ClaimType type) {
        ClaimCounter counter = counters.findForUpdate(type).orElseThrow(() -> new IllegalStateException(
                "The claim counter for " + type + " is missing; the reference data of migration V6 is broken"));
        long value = counter.getNextValue();
        counter.setNextValue(value + 1);
        counters.save(counter);
        return prefix(type) + value;
    }

    private static String prefix(ClaimType type) {
        return switch (type) {
            case MOTOR -> "CLM-";
            case PROPERTY -> "CLP-";
        };
    }
}
