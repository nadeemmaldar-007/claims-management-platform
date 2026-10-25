package com.apac.claims.claim.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/** A claim waiting in the queue: the list row plus how long it has waited and whether that breaches the target. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A submitted, unassigned claim in the officer queue.")
public record QueueItemResponse(
        @Schema(description = "Claim number.", example = "CLM-10003") String claimNumber,
        @Schema(description = "MOTOR or PROPERTY.", example = "MOTOR") ClaimType claimType,
        @Schema(description = "Always SUBMITTED in the queue.", example = "SUBMITTED") ClaimStatus status,
        @Schema(description = "Market code of the claim.", example = "IN") String marketCode,
        @Schema(description = "Currency of every amount of the claim.", example = "INR") String currencyCode,
        @Schema(description = "Amount claimed.", example = "45000.00") BigDecimal claimedAmount,
        @Schema(description = "Day of the incident.", example = "2026-10-15") LocalDate incidentDate,
        @Schema(description = "User id of the claimant.", example = "claimant.in") String claimantUserId,
        @Schema(description = "When the claim was submitted (UTC).", example = "2026-10-25T10:15:30Z")
        Instant createdAt,
        @Schema(description = "Whole hours since submission.", example = "3") long ageHours,
        @Schema(description = "True when ageHours is above the first-review target (app.sla.first-review-hours).",
                example = "false") boolean slaBreached) {
}
