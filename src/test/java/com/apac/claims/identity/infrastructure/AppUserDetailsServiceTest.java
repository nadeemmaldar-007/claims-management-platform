package com.apac.claims.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@ExtendWith(MockitoExtension.class)
class AppUserDetailsServiceTest {

    @Mock
    private UserRepository users;
    @InjectMocks
    private AppUserDetailsService service;

    private static AppUser officer(boolean active) {
        AppUser user = new AppUser("officer.in", "$2a$10$hash", "Officer India", "officer.in@test.example",
                Role.OFFICER, new Market("IN", "India", "INR", new BigDecimal("500000.00"), true));
        user.setActive(active);
        return user;
    }

    @Test
    void mapsAnActiveAccountToUserDetailsWithTheHashAndOneRoleAuthority() {
        when(users.findByUserId("officer.in")).thenReturn(Optional.of(officer(true)));

        UserDetails details = service.loadUserByUsername("officer.in");

        assertThat(details.getUsername()).isEqualTo("officer.in");
        assertThat(details.getPassword()).isEqualTo("$2a$10$hash");
        assertThat(details.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OFFICER");
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void anInactiveAccountIsReportedAsDisabled() {
        when(users.findByUserId("officer.in")).thenReturn(Optional.of(officer(false)));

        assertThat(service.loadUserByUsername("officer.in").isEnabled()).isFalse();
    }

    @Test
    void theLookupIsCaseInsensitiveAndIgnoresSurroundingSpaces() {
        when(users.findByUserId("officer.in")).thenReturn(Optional.of(officer(true)));

        service.loadUserByUsername("  Officer.IN ");

        verify(users).findByUserId("officer.in");
    }

    @Test
    void anUnknownUserIsReportedWithoutEchoingTheName() {
        when(users.findByUserId("nobody.here")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody.here"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageNotContaining("nobody.here");
    }
}
