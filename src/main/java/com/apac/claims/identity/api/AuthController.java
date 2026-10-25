package com.apac.claims.identity.api;

import com.apac.claims.identity.api.dto.LoginRequest;
import com.apac.claims.identity.api.dto.LoginResponse;
import com.apac.claims.identity.api.dto.RegisterRequest;
import com.apac.claims.identity.api.dto.UserResponse;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.service.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoints: login and self-registration. The role is a required enum query parameter, which Swagger UI
 * shows as a real dropdown.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Log in and register. Public.")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Log in",
            description = "1) choose your role in the dropdown, 2) enter userId and password in the body, "
                    + "3) Execute, 4) copy accessToken, click Authorize, paste the token only.")
    @SecurityRequirements
    @ApiResponse(responseCode = "200", description = "Logged in; the response contains the access token")
    @ApiResponse(responseCode = "400", description = "The role parameter is missing or not one of the three roles, "
            + "or the body is invalid")
    @ApiResponse(responseCode = "401", description = "Invalid user ID, password or role (the same answer for every "
            + "kind of failure)")
    @PostMapping("/login")
    public LoginResponse login(
            @Parameter(description = "The role you log in as. It must be the role of the account.",
                    required = true) @RequestParam("role") Role role,
            @Valid @RequestBody LoginRequest request) {
        return authService.login(request.userId(), request.password(), role);
    }

    @Operation(summary = "Register as a claimant",
            description = "Creates a claimant account. There is no role in the request: everyone who registers is a "
                    + "claimant. No token is returned; log in afterwards.")
    @SecurityRequirements
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "400", description = "Validation failed, for example a weak password or an unknown "
            + "market code")
    @ApiResponse(responseCode = "409", description = "The user ID or the email is already in use")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }
}
