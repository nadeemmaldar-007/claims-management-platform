package com.apac.claims.claim.api.dto;

import java.time.Instant;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.TimelineEntryType;
import com.apac.claims.claim.domain.TimelineVisibility;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry of a claim's audit timeline. A claimant only receives PUBLIC entries, and for them the {@code actor} of a
 * staff action is the text "Claims team" instead of a user id.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "An entry of the claim timeline.")
public record TimelineEntryResponse(
        @Schema(description = "What happened.", example = "ASSIGNED") TimelineEntryType entryType,
        @Schema(description = "Status before, if the status changed.", example = "SUBMITTED") ClaimStatus fromStatus,
        @Schema(description = "Status after.", example = "UNDER_REVIEW") ClaimStatus toStatus,
        @Schema(description = "Who did it: a user id, or \"Claims team\" for a claimant looking at a staff action.",
                example = "officer.in") String actor,
        @Schema(description = "Text of the entry.", example = "Claim picked up for review.") String message,
        @Schema(description = "PUBLIC entries are visible to the claimant, INTERNAL ones only to staff.",
                example = "PUBLIC") TimelineVisibility visibility,
        @Schema(description = "When it happened (UTC).", example = "2026-10-25T10:15:30Z") Instant occurredAt) {
}
