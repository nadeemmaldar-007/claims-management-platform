package com.apac.claims.claim.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.DamageType;
import com.apac.claims.claim.domain.DecisionType;
import com.apac.claims.claim.domain.PaymentMethod;
import com.apac.claims.claim.domain.PerilType;
import com.apac.claims.claim.domain.PropertyType;
import com.apac.claims.claim.domain.RejectionReason;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A claim in full. Blocks that do not apply are left out of the JSON: a motor claim has {@code motor} and no
 * {@code property}; {@code decision} appears once the claim is decided; {@code settlement} stays absent until a claim
 * is settled; {@code latestAssessment} is only ever sent to staff.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A claim with all its details.")
public record ClaimDetailResponse(
        @Schema(description = "Claim number.", example = "CLM-10003") String claimNumber,
        @Schema(description = "MOTOR or PROPERTY.", example = "MOTOR") ClaimType claimType,
        @Schema(description = "Workflow status.", example = "UNDER_REVIEW") ClaimStatus status,
        @Schema(description = "Market code of the claim.", example = "IN") String marketCode,
        @Schema(description = "Currency of every amount of the claim.", example = "INR") String currencyCode,
        @Schema(description = "Amount claimed.", example = "45000.00") BigDecimal claimedAmount,
        @Schema(description = "Amount approved, once approved.", example = "40000.00") BigDecimal approvedAmount,
        @Schema(description = "Day of the incident.", example = "2026-10-15") LocalDate incidentDate,
        @Schema(description = "User id of the claimant.", example = "claimant.in") String claimantUserId,
        @Schema(description = "User id of the assigned officer. Staff only; absent until assigned.",
                example = "officer.in") String assignedOfficerUserId,
        @Schema(description = "When the claim was submitted (UTC).", example = "2026-10-25T10:15:30Z")
        Instant createdAt,
        @Schema(description = "When the claim last changed (UTC).", example = "2026-10-25T10:15:30Z")
        Instant updatedAt,
        @Schema(description = "Policy number the claim was made under.", example = "MOT-IN-0001")
        String policyNumber,
        @Schema(description = "Sum insured of the policy when the claim was submitted.", example = "1500000.00")
        BigDecimal policySumInsured,
        @Schema(description = "Deductible of the policy when the claim was submitted.", example = "5000.00")
        BigDecimal policyDeductible,
        @Schema(description = "Where the incident happened.", example = "MG Road, Bengaluru") String incidentLocation,
        @Schema(description = "What happened.", example = "Rear-ended at a traffic signal.") String description,
        @Schema(description = "Vehicle details of a motor claim.") MotorBlock motor,
        @Schema(description = "Property details of a property claim.") PropertyBlock property,
        @Schema(description = "The decision, once the claim is approved or rejected.") DecisionBlock decision,
        @Schema(description = "Payment details, once the claim is settled.") SettlementBlock settlement,
        @Schema(description = "The newest assessment. Staff only.") AssessmentResponse latestAssessment,
        @Schema(description = "Number of information requests that still wait for an answer.", example = "0")
        long openInformationRequestCount) {

    /** Vehicle details as stored on a motor claim. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MotorBlock(
            @Schema(example = "MH12AB1234") String vehicleRegistrationNumber,
            @Schema(example = "Hyundai") String vehicleMake,
            @Schema(example = "i20") String vehicleModel,
            @Schema(example = "2021") Integer vehicleYear,
            @Schema(example = "COLLISION") DamageType damageType,
            @Schema(example = "FIR-2026-5521") String policeReportNumber) {
    }

    /** Property details as stored on a property claim. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PropertyBlock(
            @Schema(example = "Flat 402, Lakeview Apartments, Powai, Mumbai 400076") String propertyAddress,
            @Schema(example = "RESIDENTIAL") PropertyType propertyType,
            @Schema(example = "FLOOD") PerilType perilType) {
    }

    /**
     * The decision. {@code decidedBy} is a staff user id for staff and the text "Claims team" for the claimant.
     * A rejection carries the reason code and remarks as the explanation shown to the claimant.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DecisionBlock(
            @Schema(example = "APPROVED") DecisionType type,
            @Schema(description = "Reason code of a rejection.", example = "NOT_COVERED") RejectionReason reasonCode,
            @Schema(example = "Approved as assessed.") String remarks,
            @Schema(example = "officer.in") String decidedBy,
            @Schema(example = "2026-10-26T09:30:00Z") Instant decidedAt,
            @Schema(description = "Approved amount of an approval.", example = "40000.00")
            BigDecimal approvedAmount) {
    }

    /** Payment details of a settled claim. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SettlementBlock(
            @Schema(example = "BANK_TRANSFER") PaymentMethod paymentMethod,
            @Schema(example = "PAY-0123456789AB") String paymentReference,
            @Schema(example = "40000.00") BigDecimal settledAmount,
            @Schema(example = "2026-10-27T09:30:00Z") Instant settledAt) {
    }
}
