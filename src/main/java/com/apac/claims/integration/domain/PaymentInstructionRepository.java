package com.apac.claims.integration.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for payment instructions. */
public interface PaymentInstructionRepository extends JpaRepository<PaymentInstruction, Long> {

    Optional<PaymentInstruction> findByClaimNumber(String claimNumber);
}
