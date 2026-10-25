package com.apac.claims.claim.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

import com.apac.claims.claim.api.dto.ApproveRequest;
import com.apac.claims.claim.api.dto.ClaimDetailResponse;
import com.apac.claims.claim.api.dto.EscalateRequest;
import com.apac.claims.claim.api.dto.RejectRequest;
import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimAction;
import com.apac.claims.claim.domain.ClaimAssessment;
import com.apac.claims.claim.domain.ClaimAssessmentRepository;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimWorkflow;
import com.apac.claims.claim.domain.DecisionType;
import com.apac.claims.claim.domain.RejectionReason;
import com.apac.claims.claim.domain.TimelineEntryType;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.shared.event.DomainEventPublisher;
import com.apac.claims.shared.exception.BusinessRuleException;
import com.apac.claims.shared.exception.ConflictException;
import com.apac.claims.shared.exception.ErrorCode;
import com.apac.claims.shared.exception.ForbiddenException;
import com.apac.claims.shared.money.MoneyRules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escalation and the two decisions. Who may decide depends on the status: the assigned officer or a manager while the
 * claim is under review, a manager only once it is escalated. An officer can approve up to the market's escalation
 * threshold and must escalate anything above it; a manager has no limit. The remarks of an approval and the reason and
 * remarks of a rejection are the explanation the claimant reads, so nothing internal goes into them.
 */
@Service
public class ClaimDecisionService {

    private static final Logger log = LoggerFactory.getLogger(ClaimDecisionService.class);

    private final ClaimRepository claims;
    private final ClaimAssessmentRepository assessments;
    private final InformationRequestService informationRequests;
    private final ClaimActionSupport support;
    private final TimelineService timeline;
    private final DomainEventPublisher eventPublisher;
    private final ClaimEvents events;
    private final ClaimMapper mapper;
    private final Clock clock;

    public ClaimDecisionService(ClaimRepository claims, ClaimAssessmentRepository assessments,
                                InformationRequestService informationRequests, ClaimActionSupport support,
                                TimelineService timeline, DomainEventPublisher eventPublisher, ClaimEvents events,
                                ClaimMapper mapper, Clock clock) {
        this.claims = claims;
        this.assessments = assessments;
        this.informationRequests = informationRequests;
        this.support = support;
        this.timeline = timeline;
        this.eventPublisher = eventPublisher;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The assigned officer hands a claim under review to a manager. Only an officer escalates. */
    @Transactional
    public ClaimDetailResponse escalate(String claimNumber, EscalateRequest body, AppUser caller) {
        AppUser actor = support.reload(caller);
        if (actor.getRole() != Role.OFFICER) {
            throw new ForbiddenException("Only the assigned officer can escalate a claim.");
        }
        Claim claim = support.load(claimNumber, actor);
        if (!ClaimActionSupport.isAssignedTo(claim, actor)) {
            throw new ForbiddenException("Only the assigned officer can escalate this claim.");
        }
        ClaimStatus from = claim.getStatus();
        ClaimStatus to = ClaimWorkflow.nextStatus(from, ClaimAction.ESCALATE);
        claim.setStatus(to);
        claims.saveAndFlush(claim);
        timeline.record(claim, TimelineEntryType.ESCALATED, from, to, actor.getUserId(),
                "Claim escalated: " + body.remarks().trim());
        eventPublisher.publish(events.escalated(claim));
        log.info("Claim escalated claimNumber={} officer={}", claim.getClaimNumber(), actor.getUserId());
        return mapper.toDetail(claim, actor);
    }

    /**
     * Approves the claim for the recommended payout of its latest assessment, or for a smaller amount the caller names.
     * The check order is: who may act, a valid amount scale, the status, the assessment, the amount, the officer limit.
     */
    @Transactional
    public ClaimDetailResponse approve(String claimNumber, ApproveRequest body, AppUser caller) {
        AppUser actor = support.reload(caller);
        support.requireStaff(actor);
        Claim claim = support.load(claimNumber, actor);
        requireMayDecide(claim, actor, "approve");
        String currency = claim.getCurrencyCode();
        if (body != null && body.approvedAmount() != null) {
            MoneyRules.requireValidScale(body.approvedAmount(), currency, "approvedAmount");
        }
        ClaimStatus from = claim.getStatus();
        ClaimStatus to = ClaimWorkflow.nextStatus(from, ClaimAction.APPROVE);

        BigDecimal payout = assessments.findFirstByClaimOrderByIdDesc(claim)
                .filter(assessment -> assessment.isCoverageConfirmed() && assessment.getRecommendedPayout().signum() > 0)
                .map(ClaimAssessment::getRecommendedPayout)
                .orElseThrow(() -> new ConflictException(ErrorCode.ASSESSMENT_REQUIRED,
                        "The latest assessment must confirm coverage with a payout above zero before approval."));
        BigDecimal amount = body == null || body.approvedAmount() == null ? payout
                : MoneyRules.normalize(body.approvedAmount(), currency);
        if (amount.compareTo(payout) > 0) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "approvedAmount must not exceed the "
                    + "recommended payout of " + payout.toPlainString() + " " + currency + ".");
        }
        if (actor.getRole() == Role.OFFICER && amount.compareTo(claim.getMarket().getEscalationThreshold()) > 0) {
            throw new BusinessRuleException(ErrorCode.APPROVAL_LIMIT_EXCEEDED,
                    "Approved amount exceeds your approval limit; escalate to a manager.");
        }

        String remarks = body == null ? null : clean(body.remarks());
        decide(claim, actor, to, DecisionType.APPROVED, null, remarks);
        claim.setApprovedAmount(amount);
        claims.saveAndFlush(claim);
        timeline.record(claim, TimelineEntryType.APPROVED, from, to, actor.getUserId(),
                "Claim approved for " + amount.toPlainString() + " " + currency + "."
                        + (remarks == null ? "" : " " + remarks));
        eventPublisher.publish(events.approved(claim));
        log.info("Claim approved claimNumber={} by={}", claim.getClaimNumber(), actor.getUserId());
        return mapper.toDetail(claim, actor);
    }

    /** Rejects the claim; a request for information that is still open is cancelled with it. */
    @Transactional
    public ClaimDetailResponse reject(String claimNumber, RejectRequest body, AppUser caller) {
        AppUser actor = support.reload(caller);
        support.requireStaff(actor);
        Claim claim = support.load(claimNumber, actor);
        requireMayDecide(claim, actor, "reject");
        ClaimStatus from = claim.getStatus();
        ClaimStatus to = ClaimWorkflow.nextStatus(from, ClaimAction.REJECT);

        informationRequests.cancelOpenRequests(claim, actor);
        String remarks = body.remarks().trim();
        decide(claim, actor, to, DecisionType.REJECTED, body.reasonCode(), remarks);
        claims.saveAndFlush(claim);
        timeline.record(claim, TimelineEntryType.REJECTED, from, to, actor.getUserId(),
                "Claim rejected. Reason: " + body.reasonCode().name() + ". " + remarks);
        eventPublisher.publish(events.rejected(claim));
        log.info("Claim rejected claimNumber={} reason={} by={}", claim.getClaimNumber(), body.reasonCode(),
                actor.getUserId());
        return mapper.toDetail(claim, actor);
    }

    /** An escalated claim belongs to the managers; otherwise the assigned officer or a manager decides. */
    private void requireMayDecide(Claim claim, AppUser actor, String verb) {
        if (claim.getStatus() == ClaimStatus.ESCALATED) {
            if (actor.getRole() != Role.MANAGER) {
                throw new ForbiddenException("This claim is escalated; only a manager can " + verb + " it.");
            }
            return;
        }
        support.requireAssigneeOrManager(claim, actor, verb + " this claim");
    }

    private void decide(Claim claim, AppUser actor, ClaimStatus to, DecisionType type, RejectionReason reason,
                        String remarks) {
        claim.setStatus(to);
        claim.setDecisionType(type);
        claim.setDecisionReason(reason);
        claim.setDecisionRemarks(remarks);
        claim.setDecidedBy(actor);
        claim.setDecidedAt(Instant.now(clock));
    }

    private static String clean(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
