package com.apac.claims.identity.infrastructure;

import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.security.ActiveAccountChecker;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Implements the security module's {@link ActiveAccountChecker} with one existence query per request. */
@Component
public class UserAccountChecker implements ActiveAccountChecker {

    private final UserRepository users;

    public UserAccountChecker(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(String userId) {
        return userId != null && users.existsByUserIdAndActiveTrue(userId);
    }
}
