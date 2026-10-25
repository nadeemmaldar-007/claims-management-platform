package com.apac.claims.identity.service;

import java.util.Locale;

import com.apac.claims.identity.api.dto.LoginResponse;
import com.apac.claims.identity.api.dto.RegisterRequest;
import com.apac.claims.identity.api.dto.UserResponse;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.security.IssuedToken;
import com.apac.claims.security.TokenIssuer;
import com.apac.claims.shared.exception.UnauthenticatedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login and self-registration. Login has four ways to fail (unknown user, wrong password, inactive account, wrong
 * role) and all of them produce the same exception and message, so a caller cannot learn which accounts exist.
 */
@Service
public class AuthService {

    static final String INVALID_CREDENTIALS = "Invalid user ID, password or role";
    static final String TOKEN_TYPE = "Bearer";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final UserRepository users;
    private final UserService userService;
    private final TokenIssuer tokenIssuer;

    public AuthService(AuthenticationManager authenticationManager, UserRepository users, UserService userService,
                       TokenIssuer tokenIssuer) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.userService = userService;
        this.tokenIssuer = tokenIssuer;
    }

    /**
     * Authenticates the credentials and then checks that the account has the role the caller chose. Read-only
     * transaction, because the market of the account is loaded lazily when it is put into the response.
     *
     * @throws UnauthenticatedException for every kind of credential failure, always with the same message
     */
    @Transactional(readOnly = true)
    public LoginResponse login(String userId, String password, Role requestedRole) {
        String normalisedUserId = userId == null ? "" : userId.trim().toLowerCase(Locale.ROOT);
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(normalisedUserId, password));
        } catch (InternalAuthenticationServiceException e) {
            // The user lookup itself broke (for example the database is down). That is a server fault, and
            // presenting it as "invalid credentials" would hide it.
            throw new IllegalStateException("Authentication could not be performed", e);
        } catch (AuthenticationException e) {
            throw failed(normalisedUserId, requestedRole);
        }
        AppUser user = users.findByUserId(normalisedUserId).orElseThrow(() -> failed(normalisedUserId, requestedRole));
        if (user.getRole() != requestedRole) {
            throw failed(normalisedUserId, requestedRole);
        }
        IssuedToken token = tokenIssuer.issue(user.getUserId(), user.getRole().name(), UserMapper.marketCode(user));
        log.info("Login succeeded: userId={} role={}", user.getUserId(), user.getRole());
        return new LoginResponse(token.token(), TOKEN_TYPE, token.expiresInSeconds(), UserMapper.toSummary(user));
    }

    /** Registers a claimant. The role is fixed here and is never read from the request. */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        return userService.createAccount(request.userId(), request.password(), request.fullName(), request.email(),
                Role.CLAIMANT, request.marketCode());
    }

    private UnauthenticatedException failed(String userId, Role requestedRole) {
        // The user id is validated by pattern before it gets here, and is cut and cleaned again so that a caller
        // other than the controller cannot put line breaks into the log. The password is never logged.
        log.warn("Login failed: userId={} requestedRole={}", cleanForLog(userId), requestedRole);
        return new UnauthenticatedException(INVALID_CREDENTIALS);
    }

    private static String cleanForLog(String value) {
        String cut = value.length() > 30 ? value.substring(0, 30) : value;
        return cut.replaceAll("[^A-Za-z0-9._-]", "?");
    }
}
