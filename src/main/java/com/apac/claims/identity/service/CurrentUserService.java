package com.apac.claims.identity.service;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.shared.exception.UnauthenticatedException;

import org.hibernate.Hibernate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the caller of the current request to an account. The token only proves who the caller was when it was
 * issued, so the account is loaded from the database every time: a deleted or deactivated user stops working at
 * once, even with a token that has not expired.
 */
@Service
public class CurrentUserService {

    private final UserRepository users;

    public CurrentUserService(UserRepository users) {
        this.users = users;
    }

    /**
     * @return the active account behind the {@code sub} claim of the token, with its market already loaded so that
     *         it can be used by other services after this transaction has ended
     * @throws UnauthenticatedException when there is no authentication, or the user is missing or inactive
     */
    @Transactional(readOnly = true)
    public AppUser requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new UnauthenticatedException("Authentication required");
        }
        AppUser user = users.findByUserId(authentication.getName())
                .filter(AppUser::isActive)
                .orElseThrow(() -> new UnauthenticatedException("Authentication required"));
        // The entity leaves this transaction, and lazy loading would fail afterwards (open-in-view is off).
        Hibernate.initialize(user.getMarket());
        return user;
    }
}
