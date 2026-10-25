package com.apac.claims.reporting.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.config.SlaProperties;
import com.apac.claims.reporting.api.dto.MarketQueue;
import com.apac.claims.reporting.api.dto.OfficerWorkload;

import org.springframework.stereotype.Component;

/**
 * Counts what every officer has on the desk and what waits in the queue of every market, and which of it is late. All
 * ages are whole hours since submission. A queued claim is late after {@code app.sla.first-review-hours}, an open claim
 * that an officer has is late after {@code app.sla.decision-hours}; both targets are calendar hours.
 */
@Component
public class WorkloadCalculator {

    private final SlaProperties sla;

    public WorkloadCalculator(SlaProperties sla) {
        this.sla = sla;
    }

    private static long ageHours(ClaimFigures claim, Instant now) {
        return Duration.between(claim.createdAt(), now).toHours();
    }

    /** One row per officer given, also for an officer who has no claim. */
    public List<OfficerWorkload> officers(List<ReportOfficer> officers, List<ClaimFigures> claims, Instant now) {
        return officers.stream().map(officer -> {
            List<ClaimFigures> mine = claims.stream()
                    .filter(claim -> officer.userId().equals(claim.officerUserId())).toList();
            List<ClaimFigures> open = mine.stream().filter(ClaimFigures::open).toList();
            long underReview = count(mine, ClaimStatus.UNDER_REVIEW);
            long pending = count(mine, ClaimStatus.PENDING_INFORMATION);
            long escalated = count(mine, ClaimStatus.ESCALATED);
            Long oldest = open.stream().mapToLong(claim -> ageHours(claim, now)).boxed().max(Long::compare)
                    .orElse(null);
            long breaches = open.stream().filter(claim -> ageHours(claim, now) > sla.decisionHours()).count();
            return new OfficerWorkload(officer.userId(), officer.fullName(), officer.marketCode(), open.size(),
                    underReview, pending, escalated, count(mine, ClaimStatus.APPROVED), oldest, breaches);
        }).toList();
    }

    /** One row per market given: the claims that are SUBMITTED and have no officer, and how many of them are late. */
    public List<MarketQueue> queues(List<ReportMarket> markets, List<ClaimFigures> claims, Instant now) {
        return markets.stream().map(market -> {
            List<ClaimFigures> queued = claims.stream()
                    .filter(claim -> claim.marketCode().equals(market.code())
                            && claim.status() == ClaimStatus.SUBMITTED && claim.officerUserId() == null)
                    .toList();
            long late = queued.stream().filter(claim -> ageHours(claim, now) > sla.firstReviewHours()).count();
            return new MarketQueue(market.code(), queued.size(), late);
        }).toList();
    }

    /** Open claims that an officer has and that are older than the decision target, whoever the officer is. */
    public long openClaimSlaBreaches(List<ClaimFigures> claims, Instant now) {
        return claims.stream().filter(claim -> claim.open() && claim.officerUserId() != null
                && ageHours(claim, now) > sla.decisionHours()).count();
    }

    private static long count(List<ClaimFigures> claims, ClaimStatus status) {
        return claims.stream().filter(claim -> claim.status() == status).count();
    }
}
