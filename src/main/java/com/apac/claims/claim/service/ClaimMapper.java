package com.apac.claims.claim.service;

import java.math.BigDecimal;

import com.apac.claims.claim.api.dto.AssessmentResponse;
import com.apac.claims.claim.api.dto.ClaimDetailResponse;
import com.apac.claims.claim.api.dto.ClaimSummaryResponse;
import com.apac.claims.claim.api.dto.QueueItemResponse;
import com.apac.claims.claim.api.dto.TimelineEntryResponse;
import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimAssessment;
import com.apac.claims.claim.domain.ClaimAssessmentRepository;
import com.apac.claims.claim.domain.ClaimTimelineEntry;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.InformationRequestRepository;
import com.apac.claims.claim.domain.InformationRequestStatus;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.shared.money.MoneyRules;

import org.springframework.stereotype.Component;

/**
 * Turns claims into response records. It is called inside the services' transactions, because the claimant, market and
 * officer are loaded lazily. It also applies what a claimant may not see: staff user ids (the assigned officer, the
 * person who decided, the actor of a timeline entry) and the internal assessment. Amounts are shown with the number of
 * decimal places of the claim's currency, so a yen amount has none.
 */
@Component
public class ClaimMapper {

    /** What a claimant sees in place of a staff member's user id. */
    public static final String CLAIMS_TEAM = "Claims team";

    private final ClaimAssessmentRepository assessments;
    private final InformationRequestRepository informationRequests;

    public ClaimMapper(ClaimAssessmentRepository assessments, InformationRequestRepository informationRequests) {
        this.assessments = assessments;
        this.informationRequests = informationRequests;
    }

    public ClaimSummaryResponse toSummary(Claim claim, AppUser viewer) {
        String currency = claim.getCurrencyCode();
        return new ClaimSummaryResponse(claim.getClaimNumber(), claim.getClaimType(), claim.getStatus(),
                claim.getMarket().getCode(), currency, money(claim.getClaimedAmount(), currency),
                money(claim.getApprovedAmount(), currency), claim.getIncidentDate(),
                claim.getClaimant().getUserId(), officerIdFor(claim, viewer), claim.getCreatedAt(),
                claim.getUpdatedAt());
    }

    /** Loads the latest assessment (for staff only) and the number of open information requests itself. */
    public ClaimDetailResponse toDetail(Claim claim, AppUser viewer) {
        String currency = claim.getCurrencyCode();
        AssessmentResponse latest = isStaff(viewer)
                ? assessments.findFirstByClaimOrderByIdDesc(claim).map(a -> toAssessment(a, currency)).orElse(null)
                : null;
        long openRequests = informationRequests.countByClaimAndStatus(claim, InformationRequestStatus.OPEN);
        return new ClaimDetailResponse(claim.getClaimNumber(), claim.getClaimType(), claim.getStatus(),
                claim.getMarket().getCode(), currency, money(claim.getClaimedAmount(), currency),
                money(claim.getApprovedAmount(), currency), claim.getIncidentDate(),
                claim.getClaimant().getUserId(), officerIdFor(claim, viewer), claim.getCreatedAt(),
                claim.getUpdatedAt(), claim.getPolicyNumber(), money(claim.getPolicySumInsured(), currency),
                money(claim.getPolicyDeductible(), currency), claim.getIncidentLocation(), claim.getDescription(),
                motorBlock(claim), propertyBlock(claim), decisionBlock(claim, viewer), settlementBlock(claim),
                latest, openRequests);
    }

    public QueueItemResponse toQueueItem(Claim claim, long ageHours, boolean slaBreached) {
        String currency = claim.getCurrencyCode();
        return new QueueItemResponse(claim.getClaimNumber(), claim.getClaimType(), claim.getStatus(),
                claim.getMarket().getCode(), currency, money(claim.getClaimedAmount(), currency),
                claim.getIncidentDate(), claim.getClaimant().getUserId(), claim.getCreatedAt(), ageHours,
                slaBreached);
    }

    /** A claimant keeps their own user id on an entry and sees "Claims team" for every staff action. */
    public TimelineEntryResponse toTimelineEntry(ClaimTimelineEntry entry, AppUser viewer) {
        String actor = entry.getActorUserId();
        if (!isStaff(viewer) && !actor.equals(viewer.getUserId())) {
            actor = CLAIMS_TEAM;
        }
        return new TimelineEntryResponse(entry.getEntryType(), entry.getFromStatus(), entry.getToStatus(), actor,
                entry.getMessage(), entry.getVisibility(), entry.getOccurredAt());
    }

    public AssessmentResponse toAssessment(ClaimAssessment assessment, String currencyCode) {
        return new AssessmentResponse(assessment.getId(), assessment.getAssessedBy().getUserId(),
                money(assessment.getAssessedLossAmount(), currencyCode),
                money(assessment.getDeductibleApplied(), currencyCode),
                money(assessment.getRecommendedPayout(), currencyCode), assessment.isCoverageConfirmed(),
                assessment.getRecommendation(), assessment.getNotes(), assessment.getCreatedAt());
    }

    // ---- blocks -----------------------------------------------------------------------------------------------

    private static ClaimDetailResponse.MotorBlock motorBlock(Claim claim) {
        if (claim.getClaimType() != ClaimType.MOTOR) {
            return null;
        }
        return new ClaimDetailResponse.MotorBlock(claim.getVehicleRegistrationNumber(), claim.getVehicleMake(),
                claim.getVehicleModel(), claim.getVehicleYear(), claim.getDamageType(), claim.getPoliceReportNumber());
    }

    private static ClaimDetailResponse.PropertyBlock propertyBlock(Claim claim) {
        if (claim.getClaimType() != ClaimType.PROPERTY) {
            return null;
        }
        return new ClaimDetailResponse.PropertyBlock(claim.getPropertyAddress(), claim.getPropertyType(),
                claim.getPerilType());
    }

    private static ClaimDetailResponse.DecisionBlock decisionBlock(Claim claim, AppUser viewer) {
        if (claim.getDecisionType() == null) {
            return null;
        }
        String decidedBy = claim.getDecidedBy() == null ? null
                : isStaff(viewer) ? claim.getDecidedBy().getUserId() : CLAIMS_TEAM;
        return new ClaimDetailResponse.DecisionBlock(claim.getDecisionType(), claim.getDecisionReason(),
                claim.getDecisionRemarks(), decidedBy, claim.getDecidedAt(),
                money(claim.getApprovedAmount(), claim.getCurrencyCode()));
    }

    private static ClaimDetailResponse.SettlementBlock settlementBlock(Claim claim) {
        if (claim.getSettledAt() == null && claim.getPaymentReference() == null) {
            return null;
        }
        return new ClaimDetailResponse.SettlementBlock(claim.getPaymentMethod(), claim.getPaymentReference(),
                money(claim.getSettledAmount(), claim.getCurrencyCode()), claim.getSettledAt());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static boolean isStaff(AppUser viewer) {
        return viewer.getRole() != Role.CLAIMANT;
    }

    private static String officerIdFor(Claim claim, AppUser viewer) {
        return isStaff(viewer) && claim.getAssignedOfficer() != null ? claim.getAssignedOfficer().getUserId() : null;
    }

    /** Null stays null; otherwise the amount gets the decimal places of the currency (yen none, the others two). */
    private static BigDecimal money(BigDecimal amount, String currencyCode) {
        return amount == null ? null : MoneyRules.normalize(amount, currencyCode);
    }
}
