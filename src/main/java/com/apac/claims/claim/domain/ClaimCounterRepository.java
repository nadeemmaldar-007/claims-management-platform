package com.apac.claims.claim.domain;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence port for the claim number counters. */
public interface ClaimCounterRepository extends JpaRepository<ClaimCounter, ClaimType> {

    /**
     * Reads the counter under a pessimistic write lock. It must be called inside the transaction that inserts the
     * claim, so concurrent submissions of the same type queue up and never receive the same number.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ClaimCounter c where c.claimType = :type")
    Optional<ClaimCounter> findForUpdate(@Param("type") ClaimType type);
}
