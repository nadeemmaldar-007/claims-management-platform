package com.apac.claims.claim.service;

import java.math.BigDecimal;
import java.util.List;

import com.apac.claims.claim.api.dto.AssessmentResponse;
import com.apac.claims.claim.api.dto.RecordAssessmentRequest;
import com.apac.claims.claim.domain.AssessmentCalculator;
import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimAssessment;
import com.apac.claims.claim.domain.ClaimAssessmentRepository;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.TimelineEntryType;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.shared.exception.InvalidTransitionException;
import com.apac.claims.shared.money.MoneyRules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal assessments. The officer states the facts (loss, coverage, recommendation); the system computes the
 * deductible and the payout from the claim's own policy snapshots. Assessments are internal: their timeline entry is
 * INTERNAL and says only that one was recorded, and the notes and the recommendation stay out of the log as well.
 */
@Service
public class ClaimAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(ClaimAssessmentService.class);

    private final ClaimAssessmentRepository assessments;
    private final ClaimActionSupport support;
    private final TimelineService timeline;
    private final ClaimMapper mapper;

    public ClaimAssessmentService(ClaimAssessmentRepository assessments, ClaimActionSupport support,
                                  TimelineService timeline, ClaimMapper mapper) {
        this.assessments = assessments;
        this.support = support;
        this.timeline = timeline;
        this.mapper = mapper;
    }

    /** Allowed in UNDER_REVIEW and ESCALATED, for the assigned officer or a manager. */
    @Transactional
    public AssessmentResponse record(String claimNumber, RecordAssessmentRequest request, AppUser caller) {
        AppUser actor = support.reload(caller);
        support.requireStaff(actor);
        Claim claim = support.load(claimNumber, actor);
        support.requireAssigneeOrManager(claim, actor, "assess this claim");

        String currency = claim.getCurrencyCode();
        MoneyRules.requireValidScale(request.assessedLossAmount(), currency, "assessedLossAmount");
        ClaimStatus status = claim.getStatus();
        if (status != ClaimStatus.UNDER_REVIEW && status != ClaimStatus.ESCALATED) {
            throw new InvalidTransitionException("A claim that is " + status.name().toLowerCase().replace('_', ' ')
                    + " cannot be assessed.");
        }

        BigDecimal loss = MoneyRules.normalize(request.assessedLossAmount(), currency);
        BigDecimal payout = AssessmentCalculator.recommendedPayout(request.coverageConfirmed(), loss,
                claim.getPolicySumInsured(), claim.getPolicyDeductible(), currency);
        String notes = request.notes() == null || request.notes().isBlank() ? null : request.notes().trim();
        ClaimAssessment assessment = assessments.saveAndFlush(new ClaimAssessment(claim, actor, loss,
                MoneyRules.normalize(claim.getPolicyDeductible(), currency), payout, request.coverageConfirmed(),
                request.recommendation(), notes));
        timeline.record(claim, TimelineEntryType.ASSESSMENT_RECORDED, null, status, actor.getUserId(),
                "Assessment recorded.");
        log.info("Assessment recorded claimNumber={} assessmentId={}", claim.getClaimNumber(), assessment.getId());
        return mapper.toAssessment(assessment, currency);
    }

    /** Newest first. Any member of staff who may see the claim can read them. */
    @Transactional(readOnly = true)
    public List<AssessmentResponse> list(String claimNumber, AppUser caller) {
        AppUser actor = support.reload(caller);
        support.requireStaff(actor);
        Claim claim = support.load(claimNumber, actor);
        return assessments.findByClaimOrderByIdDesc(claim).stream()
                .map(assessment -> mapper.toAssessment(assessment, claim.getCurrencyCode()))
                .toList();
    }
}
