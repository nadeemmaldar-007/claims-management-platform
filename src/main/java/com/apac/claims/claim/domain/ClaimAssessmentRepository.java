package com.apac.claims.claim.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for assessments. The latest assessment is the one with the highest id, which ties never break. */
public interface ClaimAssessmentRepository extends JpaRepository<ClaimAssessment, Long> {

    Optional<ClaimAssessment> findFirstByClaimOrderByIdDesc(Claim claim);

    List<ClaimAssessment> findByClaimOrderByIdDesc(Claim claim);
}
