package com.apac.claims.notification.api;

import java.util.Set;

import com.apac.claims.identity.service.CurrentUserService;
import com.apac.claims.notification.api.dto.NotificationResponse;
import com.apac.claims.notification.api.dto.ReadAllResponse;
import com.apac.claims.notification.api.dto.UnreadCountResponse;
import com.apac.claims.notification.service.NotificationService;
import com.apac.claims.shared.api.PageRequests;
import com.apac.claims.shared.api.PageResponse;
import com.apac.claims.shared.security.Authz;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The notifications of the logged-in user. Everybody reads their own and nobody else's. */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "In-app notifications.")
public class NotificationController {

    private final NotificationService service;
    private final CurrentUserService currentUserService;

    public NotificationController(NotificationService service, CurrentUserService currentUserService) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @Operation(summary = "List my notifications",
            description = "Your own notifications, newest first. With unreadOnly=true only the unread ones. Size "
                    + "defaults to 10 and is capped at 50.")
    @ApiResponse(responseCode = "200", description = "A page of notifications")
    @ApiResponse(responseCode = "400", description = "Invalid page or size")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @PreAuthorize(Authz.AUTHENTICATED)
    @GetMapping
    public PageResponse<NotificationResponse> list(
            @Parameter(description = "Only unread notifications") @RequestParam(required = false) Boolean unreadOnly,
            @Parameter(description = "Page number, starting at 0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, default 10, maximum 50") @RequestParam(required = false) Integer size) {
        return service.list(currentUserService.requireCurrentUser(), Boolean.TRUE.equals(unreadOnly),
                PageRequests.of(page, size, null, Set.of(),
                        Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))));
    }

    @Operation(summary = "Count my unread notifications")
    @ApiResponse(responseCode = "200", description = "The number of unread notifications")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @PreAuthorize(Authz.AUTHENTICATED)
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount() {
        return new UnreadCountResponse(service.unreadCount(currentUserService.requireCurrentUser()));
    }

    @Operation(summary = "Mark one notification as read",
            description = "Someone else's notification, and an id that does not exist, are both reported as not found.")
    @ApiResponse(responseCode = "200", description = "The notification, now read")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "404", description = "Unknown id, or a notification of another user")
    @PreAuthorize(Authz.AUTHENTICATED)
    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@Parameter(description = "Id of the notification", example = "12")
                                         @PathVariable long id) {
        return service.markRead(currentUserService.requireCurrentUser(), id);
    }

    @Operation(summary = "Mark all my notifications as read")
    @ApiResponse(responseCode = "200", description = "How many notifications changed")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @PreAuthorize(Authz.AUTHENTICATED)
    @PostMapping("/read-all")
    public ReadAllResponse readAll() {
        return new ReadAllResponse(service.markAllRead(currentUserService.requireCurrentUser()));
    }
}
