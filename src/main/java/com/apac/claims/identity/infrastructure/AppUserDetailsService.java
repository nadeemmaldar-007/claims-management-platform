package com.apac.claims.identity.infrastructure;

import java.util.Locale;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.UserRepository;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapts platform accounts to Spring Security's {@link UserDetailsService}, so that the standard authentication
 * manager can check passwords. An inactive account is reported as disabled, and the single authority is
 * {@code ROLE_<role>}.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AppUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String userId = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        AppUser user = users.findByUserId(userId)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        return User.withUsername(user.getUserId())
                .password(user.getPasswordHash())
                .authorities("ROLE_" + user.getRole().name())
                .disabled(!user.isActive())
                .build();
    }
}
