package com.apac.claims.claim.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

import com.apac.claims.claim.api.dto.ClaimDetailResponse;
import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimAction;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimWorkflow;
import com.apac.claims.claim.domain.TimelineEntryType;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.shared.event.DomainEventPublisher;
import com.apac.claims.shared.exception.BusinessRuleException;
import com.apac.claims.shared.exception.ClaimAlreadyAssignedException;
import com.apac.claims.shared.exception.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who works on a claim: an officer takes a claim from the queue (pick-up), or a manager gives it to an officer of the
 * claim's market (assign). Two people reaching for the same waiting claim get one success and one 409: the claim's
 * version column decides, and the flush happens inside the method so that the loser's failure is caught here and
 * reported as CLAIM_ALREADY_ASSIGNED instead of surfacing later at commit as a generic conflict.
 */
@Service
public class ClaimAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(ClaimAssignmentService.class);

    private final ClaimRepository claims;
    private final UserRepository users;
    private final ClaimActionSupport support;
    private final TimelineService timeline;
    private final DomainEventPublisher eventPublisher;
    private final ClaimEvents events;
    private final ClaimMapper mapper;
    private final Clock clock;

    public ClaimAssignmentService(ClaimRepository claims, UserRepository users, ClaimActionSupport support,
                                  TimelineService timeline, DomainEventPublisher eventPublisher, ClaimEvents events,
                                  ClaimMapper mapper, Clock clock) {
        this.claims = claims;
        this.users = users;
        this.support = support;
        this.timeline = timeline;
        this.eventPublisher = eventPublisher;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The caller takes a SUBMITTED claim and starts the review. */
    @Transactional
    public ClaimDetailResponse pickUp(String claimNumber, AppUser caller) {
        AppUser actor = support.reload(caller);
        support.requireStaff(actor);
        Claim claim = support.load(claimNumber, actor);
        ClaimStatus from = claim.getStatus();
        if (from != ClaimStatus.SUBMITTED && from.isOpen()) {
            throw new ClaimAlreadyAssignedException("Claim " + claim.getClaimNumber()
                    + " has already been picked up by an officer.");
        }
        startReview(claim, actor, ClaimWorkflow.nextStatus(from, ClaimAction.PICK_UP));
        timeline.record(claim, TimelineEntryType.ASSIGNED, from, claim.getStatus(), actor.getUserId(),
                "Claim picked up by a claims officer; review started.");
        eventPublisher.publish(events.assigned(claim));
        log.info("Claim picked up claimNumber={} officer={}", claim.getClaimNumber(), actor.getUserId());
        return mapper.toDetail(claim, actor);
    }

    /**
     * A manager gives the claim to an officer of the claim's market. From SUBMITTED this starts the review like a
     * pick-up; from UNDER_REVIEW, PENDING_INFORMATION or ESCALATED it is a reassignment that keeps the status.
     */
    @Transactional
    public ClaimDetailResponse assign(String claimNumber, String officerUserId, AppUser caller) {
        AppUser actor = support.reload(caller);
        support.requireManager(actor);
        Claim claim = support.load(claimNumber, actor);
        ClaimStatus from = claim.getStatus();
        ClaimStatus to = ClaimWorkflow.nextStatus(from, ClaimAction.ASSIGN);
        AppUser officer = requireOfficerOfMarket(claim, officerUserId);

        if (from == ClaimStatus.SUBMITTED) {
            startReview(claim, officer, to);
            timeline.record(claim, TimelineEntryType.ASSIGNED, from, to, actor.getUserId(),
                    "Claim assigned to a claims officer; review started.");
            eventPublisher.publish(events.assigned(claim));
        } else {
            String previous = claim.getAssignedOfficer() == null ? null : claim.getAssignedOfficer().getUserId();
            if (officer.getUserId().equals(previous)) {
                throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                        "officerUserId is already the assigned officer of this claim.");
            }
            claim.setAssignedOfficer(officer);
            claim.setAssignedAt(Instant.now(clock));
            flush(claim);
            timeline.record(claim, TimelineEntryType.REASSIGNED, null, claim.getStatus(), actor.getUserId(),
                    "Reassigned from " + (previous == null ? "nobody" : previous) + " to " + officer.getUserId()
                            + " by " + actor.getUserId() + ".");
        }
        log.info("Claim assigned claimNumber={} officer={} by={}", claim.getClaimNumber(), officer.getUserId(),
                actor.getUserId());
        return mapper.toDetail(claim, actor);
    }

    private void startReview(Claim claim, AppUser officer, ClaimStatus to) {
        claim.setStatus(to);
        claim.setAssignedOfficer(officer);
        claim.setAssignedAt(Instant.now(clock));
        flush(claim);
    }

    /** Flushes inside the action, so a lost race is caught here and not at commit, outside this code. */
    private void flush(Claim claim) {
        try {
            claims.saveAndFlush(claim);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ClaimAlreadyAssignedException("Claim " + claim.getClaimNumber()
                    + " was taken or changed by someone else a moment ago.");
        }
    }

    /** One message for every way the target can be wrong, so that the answer does not reveal who exists. */
    private AppUser requireOfficerOfMarket(Claim claim, String officerUserId) {
        String userId = officerUserId == null ? "" : officerUserId.trim().toLowerCase(Locale.ROOT);
        return users.findByUserId(userId)
                .filter(AppUser::isActive)
                .filter(user -> user.getRole() == Role.OFFICER)
                .filter(user -> user.getMarket() != null
                        && user.getMarket().getId().equals(claim.getMarket().getId()))
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                        "officerUserId must be an active officer of market " + claim.getMarket().getCode() + "."));
    }
}
