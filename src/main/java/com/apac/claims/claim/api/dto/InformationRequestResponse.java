package com.apac.claims.claim.api.dto;

import java.time.Instant;

import com.apac.claims.claim.domain.InformationRequestStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A request for information. {@code overdue} is worked out when the request is read (open and past its due time), it
 * is not stored. A claimant sees "Claims team" instead of the user id of the member of staff who asked.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A question put to the claimant, and the answer once there is one.")
public record InformationRequestResponse(
        @Schema(description = "Id of the request.", example = "5") Long id,
        @Schema(description = "The question.", example = "Please send the repair estimate from the workshop.")
        String question,
        @Schema(description = "OPEN, RESPONDED or CANCELLED.", example = "OPEN") InformationRequestStatus status,
        @Schema(description = "True when the request is still open and its due time has passed.", example = "false")
        boolean overdue,
        @Schema(description = "When an answer is due (UTC).", example = "2026-11-01T10:15:30Z") Instant dueAt,
        @Schema(description = "Who asked: a user id, or \"Claims team\" for a claimant.", example = "officer.in")
        String requestedBy,
        @Schema(description = "The answer, once given.", example = "The repair estimate is 42000 INR.")
        String responseText,
        @Schema(description = "User id of the claimant who answered.", example = "claimant.in") String respondedBy,
        @Schema(description = "When the answer was given (UTC).", example = "2026-10-26T09:00:00Z")
        Instant respondedAt,
        @Schema(description = "When the question was asked (UTC).", example = "2026-10-25T10:15:30Z")
        Instant createdAt) {
}
