package com.apac.claims.claim.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for information requests. */
public interface InformationRequestRepository extends JpaRepository<InformationRequest, Long> {

    List<InformationRequest> findByClaimOrderByIdAsc(Claim claim);

    /** Looks a request up through its claim, so a request id from another claim is never found. */
    Optional<InformationRequest> findByIdAndClaim(Long id, Claim claim);

    /** Used to decide whether the claim can return to UNDER_REVIEW: it can when no request is left OPEN. */
    long countByClaimAndStatus(Claim claim, InformationRequestStatus status);
}
