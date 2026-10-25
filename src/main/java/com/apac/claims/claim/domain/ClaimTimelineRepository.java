package com.apac.claims.claim.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence port for the audit timeline; entries are only ever added. */
public interface ClaimTimelineRepository extends JpaRepository<ClaimTimelineEntry, Long> {

    List<ClaimTimelineEntry> findByClaimOrderByOccurredAtAscIdAsc(Claim claim);

    /** Claimants read the timeline through this method with {@link TimelineVisibility#PUBLIC}. */
    List<ClaimTimelineEntry> findByClaimAndVisibilityOrderByOccurredAtAscIdAsc(Claim claim,
                                                                               TimelineVisibility visibility);
}
