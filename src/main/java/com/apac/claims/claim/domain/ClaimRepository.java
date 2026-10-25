package com.apac.claims.claim.domain;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Persistence port for claims. List filters are built as {@code Specification} objects through
 * {@link JpaSpecificationExecutor}, never as JPQL with optional parameters, which PostgreSQL rejects.
 */
public interface ClaimRepository extends JpaRepository<Claim, Long>, JpaSpecificationExecutor<Claim> {

    Optional<Claim> findByClaimNumber(String claimNumber);

    /** Duplicate check: same policy, incident date and type, ignoring claims in the given status (REJECTED). */
    boolean existsByPolicyNumberAndIncidentDateAndClaimTypeAndStatusNot(String policyNumber, LocalDate incidentDate,
                                                                        ClaimType claimType, ClaimStatus status);
}
