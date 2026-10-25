package com.apac.claims.notification.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The result of marking every notification of the logged-in user as read. */
@Schema(description = "How many notifications were marked as read.")
public record ReadAllResponse(
        @Schema(description = "Notifications that changed from unread to read.", example = "3") int updated) {
}
