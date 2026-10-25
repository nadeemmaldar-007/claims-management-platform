package com.apac.claims.reporting.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.reporting.api.dto.AgingBucket;
import com.apac.claims.reporting.api.dto.AgingResponse;
import com.apac.claims.reporting.api.dto.ExposureResponse;
import com.apac.claims.reporting.api.dto.ExposureRow;
import com.apac.claims.reporting.api.dto.MarketQueue;
import com.apac.claims.reporting.api.dto.OverviewResponse;
import com.apac.claims.reporting.api.dto.PerformanceResponse;
import com.apac.claims.reporting.api.dto.WorkloadResponse;
import com.apac.claims.shared.exception.ForbiddenException;
import com.apac.claims.shared.exception.ValidationFailedException;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of the platform: the five reports, computed live from the claims every time, with nothing stored and
 * nothing cached. Every method loads the claims of the markets in the caller's scope with one query, copies the
 * figures it needs, and hands them to the calculators. The aggregation is done in memory on purpose, so that the code
 * is the same on every database and the definitions are readable Java; at a larger scale the next step is a read model
 * or SQL aggregation, which the calculators' plain inputs make easy to compare against.
 *
 * <p>Scope: an officer sees the market of their team, a manager sees every market and may narrow a report to one with
 * {@code marketCode}. The filter is applied on top of the scope, so an officer who asks for another market gets an empty
 * report (zeros), exactly as the claim list does, and learns nothing about it; an unknown market code is a 400 for
 * everybody. Claimants have no access.
 */
@Service
public class ReportingService {

    /** The default period of the performance report, today included. */
    static final int DEFAULT_PERIOD_DAYS = 30;

    private final ClaimRepository claims;
    private final UserRepository users;
    private final MarketRepository markets;
    private final ExposureCalculator exposure;
    private final AgingCalculator aging;
    private final WorkloadCalculator workload;
    private final PerformanceCalculator performance;
    private final CurrencyConverter converter;
    private final Clock clock;

    public ReportingService(ClaimRepository claims, UserRepository users, MarketRepository markets,
                            ExposureCalculator exposure, AgingCalculator aging, WorkloadCalculator workload,
                            PerformanceCalculator performance, CurrencyConverter converter, Clock clock) {
        this.claims = claims;
        this.users = users;
        this.markets = markets;
        this.exposure = exposure;
        this.aging = aging;
        this.workload = workload;
        this.performance = performance;
        this.converter = converter;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OverviewResponse overview(AppUser caller, String marketCode) {
        List<Market> scope = marketsInScope(caller, marketCode);
        List<ReportMarket> reportMarkets = reportMarkets(scope);
        List<ClaimFigures> figures = figures(scope);
        Instant now = Instant.now(clock);
        List<ExposureRow> rows = exposure.rows(reportMarkets, figures);
        List<MarketQueue> queues = workload.queues(reportMarkets, figures, now);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (ClaimStatus status : ClaimStatus.values()) {
            byStatus.put(status.name(), figures.stream().filter(claim -> claim.status() == status).count());
        }
        Map<String, Long> byType = new LinkedHashMap<>();
        for (ClaimType type : ClaimType.values()) {
            byType.put(type.name(), figures.stream().filter(claim -> claim.claimType() == type).count());
        }
        return new OverviewResponse(byStatus, byType, exposure.outstandingByMarket(reportMarkets, rows),
                exposure.total(rows).outstandingExposure(), converter.baseCurrency(), converter.rateSource(),
                queues.stream().mapToLong(MarketQueue::unassignedInQueue).sum(),
                queues.stream().mapToLong(MarketQueue::queueSlaBreaches).sum(),
                workload.openClaimSlaBreaches(figures, now), aging.buckets(figures, now, onlyMarket(reportMarkets)));
    }

    @Transactional(readOnly = true)
    public ExposureResponse exposure(AppUser caller, String marketCode) {
        List<Market> scope = marketsInScope(caller, marketCode);
        List<ExposureRow> rows = exposure.rows(reportMarkets(scope), figures(scope));
        return new ExposureResponse(converter.baseCurrency(), converter.rateSource(), rows, exposure.total(rows));
    }

    @Transactional(readOnly = true)
    public AgingResponse aging(AppUser caller, String marketCode) {
        List<Market> scope = marketsInScope(caller, marketCode);
        List<AgingBucket> buckets = aging.buckets(figures(scope), Instant.now(clock), onlyMarket(reportMarkets(scope)));
        return new AgingResponse(converter.baseCurrency(), converter.rateSource(), buckets);
    }

    @Transactional(readOnly = true)
    public WorkloadResponse workload(AppUser caller, String marketCode) {
        List<Market> scope = marketsInScope(caller, marketCode);
        List<ClaimFigures> figures = figures(scope);
        Instant now = Instant.now(clock);
        return new WorkloadResponse(workload.officers(officers(scope), figures, now),
                workload.queues(reportMarkets(scope), figures, now));
    }

    /**
     * @param from first day of the period (UTC, inclusive); default 29 days before {@code to}, so the period is 30 days
     * @param to last day of the period (UTC, inclusive); default today
     */
    @Transactional(readOnly = true)
    public PerformanceResponse performance(AppUser caller, String marketCode, LocalDate from, LocalDate to) {
        LocalDate last = to != null ? to : LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate first = from != null ? from : last.minusDays(DEFAULT_PERIOD_DAYS - 1L);
        if (first.isAfter(last)) {
            throw ValidationFailedException.forField("from", "must not be after to");
        }
        List<Market> scope = marketsInScope(caller, marketCode);
        Instant start = first.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new PerformanceResponse(first, last, performance.officers(officers(scope), figures(scope), start, end));
    }

    // ---- scope and loading ----------------------------------------------------------------------------------------------

    private List<Market> marketsInScope(AppUser caller, String marketCode) {
        if (caller.getRole() == Role.CLAIMANT) {
            throw new ForbiddenException("Only staff can read the reports");
        }
        String code = marketCode == null || marketCode.isBlank() ? null : marketCode.trim().toUpperCase(Locale.ROOT);
        if (code != null && !markets.existsByCode(code)) {
            throw ValidationFailedException.forField("marketCode", "unknown market");
        }
        List<Market> own = caller.getRole() == Role.OFFICER
                ? (caller.getMarket() == null ? List.of() : List.of(caller.getMarket()))
                : markets.findAllByOrderByIdAsc();
        return code == null ? own : own.stream().filter(market -> market.getCode().equals(code)).toList();
    }

    private static List<ReportMarket> reportMarkets(List<Market> scope) {
        return scope.stream().map(market -> new ReportMarket(market.getCode(), market.getCurrencyCode())).toList();
    }

    /** The market of the report when it covers exactly one, so that local amounts can be shown. */
    private static ReportMarket onlyMarket(List<ReportMarket> scope) {
        return scope.size() == 1 ? scope.get(0) : null;
    }

    private List<ReportOfficer> officers(List<Market> scope) {
        return scope.stream()
                .flatMap(market -> users.findByRoleAndMarketCodeAndActiveTrue(Role.OFFICER, market.getCode()).stream()
                        .map(officer -> new ReportOfficer(officer.getUserId(), officer.getFullName(), market.getCode(),
                                market.getCurrencyCode())))
                .sorted(Comparator.comparing(ReportOfficer::marketCode).thenComparing(ReportOfficer::userId))
                .toList();
    }

    private List<ClaimFigures> figures(List<Market> scope) {
        if (scope.isEmpty()) {
            return List.of();
        }
        List<Long> ids = scope.stream().map(Market::getId).toList();
        Specification<Claim> inScope = (root, query, cb) -> root.get("market").get("id").in(ids);
        return claims.findAll(inScope).stream().map(ReportingService::figuresOf).toList();
    }

    private static ClaimFigures figuresOf(Claim claim) {
        return new ClaimFigures(claim.getMarket().getCode(), claim.getCurrencyCode(), claim.getClaimType(),
                claim.getStatus(), claim.getCreatedAt(), claim.getClaimedAmount(), claim.getApprovedAmount(),
                claim.getSettledAmount(),
                claim.getAssignedOfficer() == null ? null : claim.getAssignedOfficer().getUserId(),
                claim.getAssignedAt(), claim.getDecidedAt(),
                claim.getDecidedBy() == null ? null : claim.getDecidedBy().getUserId(), claim.getDecisionType(),
                claim.getSettledAt());
    }
}
