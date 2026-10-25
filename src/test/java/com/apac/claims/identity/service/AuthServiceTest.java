package com.apac.claims.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.apac.claims.identity.api.dto.LoginResponse;
import com.apac.claims.identity.api.dto.RegisterRequest;
import com.apac.claims.identity.api.dto.UserResponse;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.security.IssuedToken;
import com.apac.claims.security.TokenIssuer;
import com.apac.claims.shared.exception.UnauthenticatedException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String GENERIC_MESSAGE = "Invalid user ID, password or role";
    private static final String PASSWORD = "Sup3r-Secret-Pw";

    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private UserRepository users;
    @Mock
    private UserService userService;
    @Mock
    private TokenIssuer tokenIssuer;

    private AuthService service;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new AuthService(authenticationManager, users, userService, tokenIssuer);
        serviceLogger = (Logger) LoggerFactory.getLogger(AuthService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
    }

    private static AppUser claimant() {
        return new AppUser("claimant.in", "hash", "Claimant India", "claimant.in@test.example", Role.CLAIMANT,
                new Market("IN", "India", "INR", new BigDecimal("500000.00"), true));
    }

    private void authenticationSucceeds() {
        when(authenticationManager.authenticate(any())).thenReturn(mockAuthentication());
    }

    private static Authentication mockAuthentication() {
        return new UsernamePasswordAuthenticationToken("claimant.in", null, List.of());
    }

    // ---- success ------------------------------------------------------------------------------------------

    @Test
    void aSuccessfulLoginReturnsTheTokenTheTypeTheLifetimeAndTheAccountSummary() {
        authenticationSucceeds();
        when(users.findByUserId("claimant.in")).thenReturn(Optional.of(claimant()));
        when(tokenIssuer.issue("claimant.in", "CLAIMANT", "IN")).thenReturn(new IssuedToken("the.jwt.token", 7200));

        LoginResponse response = service.login("claimant.in", PASSWORD, Role.CLAIMANT);

        assertThat(response.accessToken()).isEqualTo("the.jwt.token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(7200L);
        assertThat(response.user().userId()).isEqualTo("claimant.in");
        assertThat(response.user().fullName()).isEqualTo("Claimant India");
        assertThat(response.user().role()).isEqualTo(Role.CLAIMANT);
        assertThat(response.user().marketCode()).isEqualTo("IN");
    }

    @Test
    void aManagerWithoutAMarketGetsATokenWithoutAMarket() {
        authenticationSucceeds();
        AppUser manager = new AppUser("manager.apac", "hash", "APAC Manager", "m@test.example", Role.MANAGER, null);
        when(users.findByUserId("manager.apac")).thenReturn(Optional.of(manager));
        when(tokenIssuer.issue("manager.apac", "MANAGER", null)).thenReturn(new IssuedToken("t", 60));

        LoginResponse response = service.login("manager.apac", PASSWORD, Role.MANAGER);

        assertThat(response.user().marketCode()).isNull();
    }

    @Test
    void theUserIdIsTrimmedAndLowerCasedBeforeAuthenticating() {
        authenticationSucceeds();
        when(users.findByUserId("claimant.in")).thenReturn(Optional.of(claimant()));
        when(tokenIssuer.issue(any(), any(), any())).thenReturn(new IssuedToken("t", 60));

        service.login("  Claimant.IN ", PASSWORD, Role.CLAIMANT);

        ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("claimant.in");
        assertThat(captor.getValue().getCredentials()).isEqualTo(PASSWORD);
    }

    // ---- every failure looks the same ---------------------------------------------------------------------

    static Stream<AuthenticationException> credentialFailures() {
        return Stream.of(
                new BadCredentialsException("bad password"),
                new UsernameNotFoundException("no such user"),
                new DisabledException("account is disabled"),
                new LockedException("account is locked"));
    }

    @ParameterizedTest
    @MethodSource("credentialFailures")
    void everyAuthenticationFailureGivesTheSameExceptionAndMessage(AuthenticationException failure) {
        when(authenticationManager.authenticate(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.login("claimant.in", PASSWORD, Role.CLAIMANT))
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage(GENERIC_MESSAGE)
                .hasNoCause();
        verifyNoInteractions(tokenIssuer);
    }

    @Test
    void aWrongRoleGivesTheSameMessageAsABadPassword() {
        authenticationSucceeds();
        when(users.findByUserId("claimant.in")).thenReturn(Optional.of(claimant()));

        assertThatThrownBy(() -> service.login("claimant.in", PASSWORD, Role.MANAGER))
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage(GENERIC_MESSAGE);
        verifyNoInteractions(tokenIssuer);
    }

    @Test
    void anAccountThatVanishesBetweenAuthenticationAndLookupGivesTheSameMessage() {
        authenticationSucceeds();
        when(users.findByUserId("claimant.in")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("claimant.in", PASSWORD, Role.CLAIMANT))
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage(GENERIC_MESSAGE);
    }

    @Test
    void anInfrastructureFailureIsNotPresentedAsInvalidCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new InternalAuthenticationServiceException("database is down"));

        assertThatThrownBy(() -> service.login("claimant.in", PASSWORD, Role.CLAIMANT))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(UnauthenticatedException.class);
    }

    // ---- logging ------------------------------------------------------------------------------------------

    @Test
    void aFailureIsLoggedAtWarnWithTheUserIdAndRoleButNeverThePassword() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> service.login("Claimant.IN", PASSWORD, Role.OFFICER))
                .isInstanceOf(UnauthenticatedException.class);

        assertThat(logs.list).hasSize(1);
        ILoggingEvent event = logs.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("claimant.in").contains("OFFICER").doesNotContain(PASSWORD);
    }

    @Test
    void aSuccessIsLoggedWithoutThePasswordOrTheToken() {
        authenticationSucceeds();
        when(users.findByUserId("claimant.in")).thenReturn(Optional.of(claimant()));
        when(tokenIssuer.issue(any(), any(), any())).thenReturn(new IssuedToken("secret.jwt.value", 60));

        service.login("claimant.in", PASSWORD, Role.CLAIMANT);

        assertThat(logs.list).isNotEmpty().allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain(PASSWORD).doesNotContain("secret.jwt.value"));
    }

    @Test
    void lineBreaksInAUserIdCannotReachTheLog() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> service.login("evil\nFORGED LOG LINE", PASSWORD, Role.CLAIMANT))
                .isInstanceOf(UnauthenticatedException.class);

        // Every character outside the allowed set is replaced, so the id stays on one line as one token.
        assertThat(logs.list.get(0).getFormattedMessage())
                .doesNotContain("\n")
                .contains("userId=evil?forged?log?line requestedRole=CLAIMANT");
    }

    // ---- registration -------------------------------------------------------------------------------------

    @Test
    void registrationAlwaysCreatesAClaimantWhateverTheRequestSays() {
        RegisterRequest request = new RegisterRequest("new.claimant", "Claimant@123", "New Claimant",
                "new.claimant@test.example", "IN");
        UserResponse created = new UserResponse("new.claimant", "New Claimant", "new.claimant@test.example",
                Role.CLAIMANT, "IN", true, null);
        when(userService.createAccount(eq("new.claimant"), eq("Claimant@123"), eq("New Claimant"),
                eq("new.claimant@test.example"), eq(Role.CLAIMANT), eq("IN"))).thenReturn(created);

        UserResponse response = service.register(request);

        assertThat(response).isSameAs(created);
        verify(userService).createAccount("new.claimant", "Claimant@123", "New Claimant",
                "new.claimant@test.example", Role.CLAIMANT, "IN");
    }
}
