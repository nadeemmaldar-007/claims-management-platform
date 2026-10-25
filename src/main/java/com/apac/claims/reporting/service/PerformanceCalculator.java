package com.apac.claims.reporting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import com.apac.claims.claim.domain.DecisionType;
import com.apac.claims.reporting.api.dto.OfficerPerformance;

import org.springframework.stereotype.Component;

/**
 * Works out the results of every officer for a period. The definitions, which the response documents too:
 * <ul>
 *   <li><b>Decided</b>: claims the officer approved or rejected, with the decision time inside the period. A decision of a
 *       manager is counted for nobody, because only officers have a row.</li>
 *   <li><b>Approval rate</b>: approved divided by decided, four decimals; null when nothing was decided.</li>
 *   <li><b>Settled</b>: claims assigned to the officer whose settlement time is inside the period (the claim does not
 *       record who paid, only who has it).</li>
 *   <li><b>Hours to first review</b>: submission to assignment, averaged over the claims assigned to the officer in the
 *       period; a reassignment counts from the new assignment.</li>
 *   <li><b>Hours to decision</b>: submission to decision, averaged over the claims the officer decided in the period.</li>
 *   <li><b>Total approved</b>: the approved amounts of the claims the officer approved in the period, in the currency
 *       of the officer's market.</li>
 * </ul>
 * The period is a half-open range of instants, from the start of the first day to the start of the day after the last.
 */
@Component
public class PerformanceCalculator {

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private final CurrencyConverter converter;

    public PerformanceCalculator(CurrencyConverter converter) {
        this.converter = converter;
    }

    private static boolean within(Instant time, Instant from, Instant toExclusive) {
        return time != null && !time.isBefore(from) && time.isBefore(toExclusive);
    }

    /** One row per officer given, also for an officer with nothing in the period. */
    public List<OfficerPerformance> officers(List<ReportOfficer> officers, List<ClaimFigures> claims, Instant from,
                                             Instant toExclusive) {
        return officers.stream().map(officer -> {
            List<ClaimFigures> decided = claims.stream()
                    .filter(claim -> officer.userId().equals(claim.decidedByUserId()) && claim.decisionType() != null
                            && within(claim.decidedAt(), from, toExclusive))
                    .toList();
            List<ClaimFigures> approved = decided.stream()
                    .filter(claim -> claim.decisionType() == DecisionType.APPROVED).toList();
            long rejected = decided.size() - approved.size();
            long settled = claims.stream().filter(claim -> officer.userId().equals(claim.officerUserId())
                    && within(claim.settledAt(), from, toExclusive)).count();
            List<ClaimFigures> assigned = claims.stream().filter(claim -> officer.userId().equals(claim.officerUserId())
                    && within(claim.assignedAt(), from, toExclusive)).toList();
            BigDecimal rate = decided.isEmpty() ? null : BigDecimal.valueOf(approved.size())
                    .divide(BigDecimal.valueOf(decided.size()), 4, RoundingMode.HALF_UP);
            BigDecimal totalApproved = ExposureCalculator.sum(approved.stream().map(ClaimFigures::approvedAmount));
            return new OfficerPerformance(officer.userId(), officer.fullName(), officer.marketCode(), decided.size(),
                    approved.size(), rejected, rate, settled,
                    averageHours(assigned.stream().map(claim -> Duration.between(claim.createdAt(), claim.assignedAt()))),
                    averageHours(decided.stream().map(claim -> Duration.between(claim.createdAt(), claim.decidedAt()))),
                    converter.local(totalApproved, officer.currencyCode()), officer.currencyCode());
        }).toList();
    }

    /** The mean of the durations in hours with two decimals, or null when there are none. */
    static BigDecimal averageHours(Stream<Duration> durations) {
        List<Duration> all = durations.toList();
        if (all.isEmpty()) {
            return null;
        }
        long millis = all.stream().mapToLong(Duration::toMillis).sum();
        return BigDecimal.valueOf(millis).divide(BigDecimal.valueOf(MILLIS_PER_HOUR * all.size()), 2,
                RoundingMode.HALF_UP);
    }
}
