package com.apac.claims.claim.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.apac.claims.claim.api.dto.ClaimDetailResponse;
import com.apac.claims.claim.api.dto.ClaimSummaryResponse;
import com.apac.claims.claim.api.dto.QueueItemResponse;
import com.apac.claims.claim.api.dto.TimelineEntryResponse;
import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimTimelineRepository;
import com.apac.claims.claim.domain.TimelineVisibility;
import com.apac.claims.config.SlaProperties;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.shared.api.PageRequests;
import com.apac.claims.shared.api.PageResponse;
import com.apac.claims.shared.exception.ForbiddenException;
import com.apac.claims.shared.exception.ValidationFailedException;
import com.apac.claims.shared.exception.ValidationFailedException.FieldError;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of claims: the filtered list, the officer queue, one claim and its timeline. Every method starts from
 * {@link ClaimScope}, so nobody ever reads a claim outside their scope. Entities are mapped to response records inside
 * the transaction, and the lists load their associations in the page query itself.
 */
@Service
public class ClaimQueryService {

    /** Fields the claim list may be sorted by. */
    static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "claimedAmount", "incidentDate", "claimNumber");

    private final ClaimRepository claims;
    private final ClaimTimelineRepository timeline;
    private final ClaimScope scope;
    private final ClaimMapper mapper;
    private final SlaProperties sla;
    private final Clock clock;

    public ClaimQueryService(ClaimRepository claims, ClaimTimelineRepository timeline, ClaimScope scope,
                             ClaimMapper mapper, SlaProperties sla, Clock clock) {
        this.claims = claims;
        this.timeline = timeline;
        this.scope = scope;
        this.mapper = mapper;
        this.sla = sla;
        this.clock = clock;
    }

    /** The page request of the claim list: size capped at 50, sort whitelisted, newest first by default. */
    public static Pageable pageRequest(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, sort, SORTABLE_FIELDS, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /** The page request of the queue: oldest first, size capped at 50. */
    public static Pageable queuePageRequest(Integer page, Integer size) {
        return PageRequests.of(page, size, null, Set.of(), Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    @Transactional(readOnly = true)
    public PageResponse<ClaimSummaryResponse> list(AppUser user, ClaimFilters filters, Pageable pageable) {
        Specification<Claim> spec = scope.visibleTo(user).and(filterSpecification(filters, user));
        return PageResponse.of(claims.findAll(spec, withIdTieBreaker(pageable)),
                claim -> mapper.toSummary(claim, user));
    }

    /** Submitted claims that nobody has picked up yet, inside the user's scope, oldest first. Staff only. */
    @Transactional(readOnly = true)
    public PageResponse<QueueItemResponse> queue(AppUser user, Pageable pageable) {
        if (user.getRole() == Role.CLAIMANT) {
            throw new ForbiddenException("Only staff can see the queue");
        }
        Specification<Claim> spec = scope.visibleTo(user)
                .and((root, query, cb) -> cb.equal(root.get("status"), ClaimStatus.SUBMITTED))
                .and((root, query, cb) -> cb.isNull(root.get("assignedOfficer")));
        Instant now = Instant.now(clock);
        return PageResponse.of(claims.findAll(spec, withIdTieBreaker(pageable)), claim -> {
            long ageHours = Duration.between(claim.getCreatedAt(), now).toHours();
            return mapper.toQueueItem(claim, ageHours, ageHours > sla.firstReviewHours());
        });
    }

    @Transactional(readOnly = true)
    public ClaimDetailResponse get(String claimNumber, AppUser user) {
        return mapper.toDetail(scope.requireVisible(claimNumber, user), user);
    }

    /** Staff see every entry; a claimant sees the PUBLIC ones, with staff user ids shown as "Claims team". */
    @Transactional(readOnly = true)
    public List<TimelineEntryResponse> timeline(String claimNumber, AppUser user) {
        Claim claim = scope.requireVisible(claimNumber, user);
        var entries = user.getRole() == Role.CLAIMANT
                ? timeline.findByClaimAndVisibilityOrderByOccurredAtAscIdAsc(claim, TimelineVisibility.PUBLIC)
                : timeline.findByClaimOrderByOccurredAtAscIdAsc(claim);
        return entries.stream().map(entry -> mapper.toTimelineEntry(entry, user)).toList();
    }

    /** Only the filters that were given add a predicate, so no query ever contains an optional-null comparison. */
    private Specification<Claim> filterSpecification(ClaimFilters filters, AppUser user) {
        checkDateRange(filters);
        Specification<Claim> spec = (root, query, cb) -> cb.conjunction();
        if (filters.status() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), filters.status()));
        }
        if (filters.claimType() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("claimType"), filters.claimType()));
        }
        if (filters.marketCode() != null && !filters.marketCode().isBlank()) {
            String code = filters.marketCode().trim().toUpperCase(Locale.ROOT);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("market").get("code"), code));
        }
        if (filters.assignedTo() != null && !filters.assignedTo().isBlank()) {
            String assignedTo = filters.assignedTo().trim();
            spec = "me".equalsIgnoreCase(assignedTo)
                    ? spec.and((root, query, cb) -> cb.equal(root.get("assignedOfficer").get("id"), user.getId()))
                    : spec.and((root, query, cb) -> cb.equal(root.get("assignedOfficer").get("userId"),
                            assignedTo.toLowerCase(Locale.ROOT)));
        }
        if (filters.unassigned() != null) {
            spec = filters.unassigned()
                    ? spec.and((root, query, cb) -> cb.isNull(root.get("assignedOfficer")))
                    : spec.and((root, query, cb) -> cb.isNotNull(root.get("assignedOfficer")));
        }
        if (filters.submittedFrom() != null) {
            Instant from = startOfDayUtc(filters.submittedFrom());
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from));
        }
        if (filters.submittedTo() != null) {
            // Inclusive end date: everything before the start of the following day.
            Instant before = startOfDayUtc(filters.submittedTo().plusDays(1));
            spec = spec.and((root, query, cb) -> cb.lessThan(root.<Instant>get("createdAt"), before));
        }
        return spec;
    }

    private static void checkDateRange(ClaimFilters filters) {
        if (filters.submittedFrom() != null && filters.submittedTo() != null
                && filters.submittedFrom().isAfter(filters.submittedTo())) {
            throw new ValidationFailedException("Request validation failed",
                    List.of(new FieldError("submittedFrom", "must not be after submittedTo")));
        }
    }

    private static Instant startOfDayUtc(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** The id is added as a tie-breaker, because createdAt alone does not give a stable order across pages. */
    private static Pageable withIdTieBreaker(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort().and(Sort.by(Sort.Direction.ASC, "id")));
    }
}
