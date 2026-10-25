package com.apac.claims.claim.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of a claim list. Fields without a value are left out of the JSON. A claimant never receives staff user
 * ids, so {@code assignedOfficerUserId} is only present for staff.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A claim in a list.")
public record ClaimSummaryResponse(
        @Schema(description = "Claim number: CLM- for motor, CLP- for property.", example = "CLM-10003")
        String claimNumber,
        @Schema(description = "MOTOR or PROPERTY.", example = "MOTOR") ClaimType claimType,
        @Schema(description = "Workflow status.", example = "SUBMITTED") ClaimStatus status,
        @Schema(description = "Market code of the claim.", example = "IN") String marketCode,
        @Schema(description = "Currency of every amount of the claim.", example = "INR") String currencyCode,
        @Schema(description = "Amount claimed.", example = "45000.00") BigDecimal claimedAmount,
        @Schema(description = "Amount approved, once the claim is approved.", example = "40000.00")
        BigDecimal approvedAmount,
        @Schema(description = "Day of the incident.", example = "2026-10-15") LocalDate incidentDate,
        @Schema(description = "User id of the claimant.", example = "claimant.in") String claimantUserId,
        @Schema(description = "User id of the assigned officer. Staff only; absent until the claim is assigned.",
                example = "officer.in") String assignedOfficerUserId,
        @Schema(description = "When the claim was submitted (UTC).", example = "2026-10-25T10:15:30Z")
        Instant createdAt,
        @Schema(description = "When the claim last changed (UTC).", example = "2026-10-25T10:15:30Z")
        Instant updatedAt) {
}
