package com.apac.claims.notification.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** How many notifications of the logged-in user are unread. */
@Schema(description = "The number of unread notifications.")
public record UnreadCountResponse(
        @Schema(description = "Unread notifications of the logged-in user.", example = "3") long unreadCount) {
}
