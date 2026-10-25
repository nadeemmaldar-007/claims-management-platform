package com.apac.claims.claim.domain;

import java.util.Optional;

import com.apac.claims.identity.domain.AppUser;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for submission idempotency keys. */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByUserAndIdempotencyKey(AppUser user, String idempotencyKey);
}
