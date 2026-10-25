package com.apac.claims.notification.api.dto;

import java.time.Instant;

import com.apac.claims.notification.domain.NotificationType;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/** An in-app notification of the logged-in user. {@code readAt} is absent until the notification is read. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A notification for the logged-in user.")
public record NotificationResponse(
        @Schema(description = "Id of the notification, used to mark it as read.", example = "12") Long id,
        @Schema(description = "The claim the notification is about.", example = "CLM-10003") String claimNumber,
        @Schema(description = "What happened.", example = "CLAIM_APPROVED") NotificationType type,
        @Schema(description = "Short title.", example = "Claim approved") String title,
        @Schema(description = "The text of the notification.", example = "Your claim CLM-10003 was approved for 39000.00 INR.")
        String message,
        @Schema(description = "True once the user has read it.", example = "false") boolean read,
        @Schema(description = "When it was created (UTC).", example = "2026-10-25T15:33:00Z") Instant createdAt,
        @Schema(description = "When it was read (UTC); absent while unread.", example = "2026-10-25T16:00:00Z")
        Instant readAt) {
}
