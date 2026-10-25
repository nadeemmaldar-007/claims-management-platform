package com.apac.claims.identity.api;

import com.apac.claims.identity.api.dto.CreateUserRequest;
import com.apac.claims.identity.api.dto.UserResponse;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.service.CurrentUserService;
import com.apac.claims.identity.service.UserService;
import com.apac.claims.shared.api.PageResponse;
import com.apac.claims.shared.security.Authz;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The profile of the logged-in user, and user management for managers. */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Own profile and user management.")
public class UserController {

    private final UserService userService;
    private final CurrentUserService currentUserService;

    public UserController(UserService userService, CurrentUserService currentUserService) {
        this.userService = userService;
        this.currentUserService = currentUserService;
    }

    @Operation(summary = "Own profile", description = "Returns the account of the logged-in user.")
    @ApiResponse(responseCode = "200", description = "The profile")
    @ApiResponse(responseCode = "401", description = "No valid token, or the account no longer exists or is inactive")
    @PreAuthorize(Authz.AUTHENTICATED)
    @GetMapping("/me")
    public UserResponse me() {
        return userService.getMe(currentUserService.requireCurrentUser());
    }

    @Operation(summary = "Create a staff account",
            description = "A manager creates an OFFICER (market required) or a MANAGER (no market). Claimants "
                    + "register themselves.")
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "400", description = "Validation failed, or the market rule for the role is broken")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only a MANAGER may do this")
    @ApiResponse(responseCode = "409", description = "The user ID or the email is already in use")
    @PreAuthorize(Authz.MANAGER)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.createStaff(request);
    }

    @Operation(summary = "List users",
            description = "A manager lists accounts, optionally filtered by role and market. Size defaults to 10 and "
                    + "is capped at 50. Sort by userId or createdAt, for example sort=createdAt,desc.")
    @ApiResponse(responseCode = "200", description = "A page of users")
    @ApiResponse(responseCode = "400", description = "Invalid filter, page, size or sort")
    @ApiResponse(responseCode = "401", description = "No valid token")
    @ApiResponse(responseCode = "403", description = "Only a MANAGER may do this")
    @PreAuthorize(Authz.MANAGER)
    @GetMapping
    public PageResponse<UserResponse> list(
            @Parameter(description = "Only users with this role") @RequestParam(required = false) Role role,
            @Parameter(description = "Only users of this market, for example IN")
            @RequestParam(required = false) String marketCode,
            @Parameter(description = "Page number, starting at 0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, default 10, maximum 50") @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort: userId or createdAt, optionally followed by ,asc or ,desc")
            @RequestParam(required = false) String sort) {
        return userService.list(role, marketCode, UserService.pageRequest(page, size, sort));
    }
}
