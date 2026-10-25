package com.apac.claims.claim.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.lang.Nullable;

/**
 * Persistence port for claims. List filters are built as {@code Specification} objects through
 * {@link JpaSpecificationExecutor}, never as JPQL with optional parameters, which PostgreSQL rejects. The entity
 * graphs load the associations that the response records need in the same query, so a page of claims does not cost
 * one extra query per row.
 */
public interface ClaimRepository extends JpaRepository<Claim, Long>, JpaSpecificationExecutor<Claim> {

    @EntityGraph(attributePaths = {"claimant", "market", "assignedOfficer", "decidedBy"})
    Optional<Claim> findByClaimNumber(String claimNumber);

    /** The page query used by lists and the queue; only to-one associations are fetched, so paging stays in SQL. */
    @Override
    @EntityGraph(attributePaths = {"claimant", "market", "assignedOfficer"})
    Page<Claim> findAll(@Nullable Specification<Claim> spec, Pageable pageable);

    /**
     * Every claim that matches, for the reports, which read all of them and add them up in memory. The market, the
     * assigned officer and the person who decided are loaded in the same query, so the number of queries does not
     * grow with the number of claims.
     */
    @Override
    @EntityGraph(attributePaths = {"market", "assignedOfficer", "decidedBy"})
    List<Claim> findAll(@Nullable Specification<Claim> spec);

    /** Duplicate check: same policy, incident date and type, ignoring claims in the given status (REJECTED). */
    boolean existsByPolicyNumberAndIncidentDateAndClaimTypeAndStatusNot(String policyNumber, LocalDate incidentDate,
                                                                        ClaimType claimType, ClaimStatus status);
}
