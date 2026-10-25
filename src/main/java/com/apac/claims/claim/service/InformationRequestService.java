package com.apac.claims.claim.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.apac.claims.claim.api.dto.ClaimDetailResponse;
import com.apac.claims.claim.api.dto.CreateInformationRequest;
import com.apac.claims.claim.api.dto.InformationRequestResponse;
import com.apac.claims.claim.api.dto.RespondToInformationRequest;
import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimAction;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimWorkflow;
import com.apac.claims.claim.domain.InformationRequest;
import com.apac.claims.claim.domain.InformationRequestRepository;
import com.apac.claims.claim.domain.InformationRequestStatus;
import com.apac.claims.claim.domain.TimelineEntryType;
import com.apac.claims.config.SlaProperties;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.shared.event.DomainEventPublisher;
import com.apac.claims.shared.exception.ConflictException;
import com.apac.claims.shared.exception.ErrorCode;
import com.apac.claims.shared.exception.ForbiddenException;
import com.apac.claims.shared.exception.ResourceNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Questions from staff to the claimant. Asking moves the claim to PENDING_INFORMATION; once no request is left OPEN
 * (answered or cancelled) the claim goes back to UNDER_REVIEW. A request is always loaded through its claim, so an id
 * that belongs to another claim is a 404 and never leaks across claims.
 */
@Service
public class InformationRequestService {

    private static final Logger log = LoggerFactory.getLogger(InformationRequestService.class);

    private final ClaimRepository claims;
    private final InformationRequestRepository requests;
    private final ClaimActionSupport support;
    private final TimelineService timeline;
    private final DomainEventPublisher eventPublisher;
    private final ClaimEvents events;
    private final ClaimMapper mapper;
    private final SlaProperties sla;
    private final Clock clock;

    public InformationRequestService(ClaimRepository claims, InformationRequestRepository requests,
                                     ClaimActionSupport support, TimelineService timeline,
                                     DomainEventPublisher eventPublisher, ClaimEvents events, ClaimMapper mapper,
                                     SlaProperties sla, Clock clock) {
        this.claims = claims;
        this.requests = requests;
        this.support = support;
        this.timeline = timeline;
        this.eventPublisher = eventPublisher;
        this.events = events;
        this.mapper = mapper;
        this.sla = sla;
        this.clock = clock;
    }

    /** Staff ask the claimant a question; the claim must be UNDER_REVIEW, the caller its officer or a manager. */
    @Transactional
    public InformationRequestResponse create(String claimNumber, CreateInformationRequest body, AppUser caller) {
        AppUser actor = support.reload(caller);
        Claim claim = support.load(claimNumber, actor);
        support.requireAssigneeOrManager(claim, actor, "ask the claimant for information");
        ClaimStatus from = claim.getStatus();
        ClaimStatus to = ClaimWorkflow.nextStatus(from, ClaimAction.REQUEST_INFORMATION);

        int days = body.dueInDays() == null ? sla.informationRequestDefaultDays() : body.dueInDays();
        String question = body.question().trim();
        InformationRequest request = requests.saveAndFlush(new InformationRequest(claim, actor, question,
                Instant.now(clock).plus(Duration.ofDays(days))));
        claim.setStatus(to);
        claims.saveAndFlush(claim);
        timeline.record(claim, TimelineEntryType.INFORMATION_REQUESTED, from, to, actor.getUserId(),
                "Information requested: " + question);
        eventPublisher.publish(events.informationRequested(claim));
        log.info("Information requested claimNumber={} requestId={} dueInDays={}", claim.getClaimNumber(),
                request.getId(), days);
        return mapper.toInformationRequest(request, actor, Instant.now(clock));
    }

    /** Scoped like the claim: a claimant reads the requests of their own claims, staff those of their scope. */
    @Transactional(readOnly = true)
    public List<InformationRequestResponse> list(String claimNumber, AppUser caller) {
        AppUser actor = support.reload(caller);
        Claim claim = support.load(claimNumber, actor);
        Instant now = Instant.now(clock);
        return requests.findByClaimOrderByIdAsc(claim).stream()
                .map(request -> mapper.toInformationRequest(request, actor, now))
                .toList();
    }

    /** Only the claimant of the claim answers, and only an OPEN request. */
    @Transactional
    public ClaimDetailResponse respond(String claimNumber, long requestId, RespondToInformationRequest body,
                                       AppUser caller) {
        AppUser actor = support.reload(caller);
        if (actor.getRole() != Role.CLAIMANT) {
            throw new ForbiddenException("Only the claimant can answer an information request");
        }
        Claim claim = support.load(claimNumber, actor);
        InformationRequest request = requireOpen(claim, requestId);

        request.setStatus(InformationRequestStatus.RESPONDED);
        request.setResponseText(body.responseText().trim());
        request.setRespondedBy(actor);
        request.setRespondedAt(Instant.now(clock));
        requests.saveAndFlush(request);
        ClaimStatus from = claim.getStatus();
        ClaimStatus to = resumeReviewWhenNothingIsOpen(claim);
        timeline.record(claim, TimelineEntryType.INFORMATION_PROVIDED, from == to ? null : from, to,
                actor.getUserId(), "The claimant provided the requested information.");
        eventPublisher.publish(events.informationProvided(claim));
        log.info("Information provided claimNumber={} requestId={}", claim.getClaimNumber(), requestId);
        return mapper.toDetail(claim, actor);
    }

    /** Staff withdraw a question that is still OPEN; no event is raised. */
    @Transactional
    public ClaimDetailResponse cancel(String claimNumber, long requestId, AppUser caller) {
        AppUser actor = support.reload(caller);
        Claim claim = support.load(claimNumber, actor);
        support.requireAssigneeOrManager(claim, actor, "cancel an information request");
        InformationRequest request = requireOpen(claim, requestId);

        request.setStatus(InformationRequestStatus.CANCELLED);
        requests.saveAndFlush(request);
        ClaimStatus from = claim.getStatus();
        ClaimStatus to = resumeReviewWhenNothingIsOpen(claim);
        timeline.record(claim, TimelineEntryType.INFORMATION_REQUEST_CANCELLED, from == to ? null : from, to,
                actor.getUserId(), "Information request cancelled.");
        log.info("Information request cancelled claimNumber={} requestId={}", claim.getClaimNumber(), requestId);
        return mapper.toDetail(claim, actor);
    }

    /**
     * Rejecting a claim ends the waiting: every request still OPEN becomes CANCELLED, each with its own timeline entry.
     * The decision service calls this before it changes the status, in the same transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelOpenRequests(Claim claim, AppUser actor) {
        for (InformationRequest request : requests.findByClaimOrderByIdAsc(claim)) {
            if (request.getStatus() == InformationRequestStatus.OPEN) {
                request.setStatus(InformationRequestStatus.CANCELLED);
                requests.saveAndFlush(request);
                timeline.record(claim, TimelineEntryType.INFORMATION_REQUEST_CANCELLED, null, claim.getStatus(),
                        actor.getUserId(), "Information request cancelled because the claim was rejected.");
            }
        }
    }

    private InformationRequest requireOpen(Claim claim, long requestId) {
        InformationRequest request = requests.findByIdAndClaim(requestId, claim)
                .orElseThrow(() -> ResourceNotFoundException.of("Information request", String.valueOf(requestId)));
        if (request.getStatus() != InformationRequestStatus.OPEN) {
            throw new ConflictException(ErrorCode.INFORMATION_REQUEST_NOT_OPEN,
                    "This information request is no longer open.");
        }
        return request;
    }

    /** The waiting is over when no request is left OPEN: the claim goes back to UNDER_REVIEW. */
    private ClaimStatus resumeReviewWhenNothingIsOpen(Claim claim) {
        if (requests.countByClaimAndStatus(claim, InformationRequestStatus.OPEN) > 0) {
            return claim.getStatus();
        }
        ClaimStatus next = ClaimWorkflow.nextStatus(claim.getStatus(), ClaimAction.RECEIVE_INFORMATION);
        claim.setStatus(next);
        claims.saveAndFlush(claim);
        return next;
    }
}
