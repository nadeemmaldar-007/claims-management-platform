package com.apac.claims.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.shared.exception.UnauthenticatedException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock
    private UserRepository users;
    @InjectMocks
    private CurrentUserService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static AppUser user(boolean active) {
        AppUser user = new AppUser("claimant.in", "hash", "Claimant India", "claimant.in@test.example",
                Role.CLAIMANT, new Market("IN", "India", "INR", new BigDecimal("500000.00"), true));
        user.setActive(active);
        return user;
    }

    private static void authenticateAs(String name) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_CLAIMANT"))));
    }

    @Test
    void returnsTheActiveAccountBehindTheAuthenticationName() {
        authenticateAs("claimant.in");
        AppUser account = user(true);
        when(users.findByUserId("claimant.in")).thenReturn(Optional.of(account));

        assertThat(service.requireCurrentUser()).isSameAs(account);
    }

    @Test
    void failsWhenNobodyIsAuthenticated() {
        assertThatThrownBy(() -> service.requireCurrentUser()).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void failsForAnAnonymousAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(() -> service.requireCurrentUser()).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void failsWhenTheAccountNoLongerExists() {
        authenticateAs("gone.user");
        when(users.findByUserId("gone.user")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireCurrentUser())
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage("Authentication required");
    }

    @Test
    void failsWhenTheAccountHasBeenDeactivated() {
        authenticateAs("claimant.in");
        when(users.findByUserId("claimant.in")).thenReturn(Optional.of(user(false)));

        assertThatThrownBy(() -> service.requireCurrentUser())
                .isInstanceOf(UnauthenticatedException.class)
                .hasMessage("Authentication required");
    }

    @Test
    void aManagerWithoutAMarketIsReturnedWithoutTryingToLoadOne() {
        authenticateAs("manager.apac");
        AppUser manager = new AppUser("manager.apac", "hash", "APAC Manager", "m@test.example", Role.MANAGER, null);
        when(users.findByUserId("manager.apac")).thenReturn(Optional.of(manager));

        assertThat(service.requireCurrentUser().getMarket()).isNull();
    }
}
