package com.apac.claims.security;

/**
 * Port that tells the security filter chain whether the account named in a token still exists and may log in.
 * It is declared here and implemented by the identity module, so {@code security} stays free of any feature module
 * while still being able to reject the token of a deleted or deactivated user on every request.
 */
public interface ActiveAccountChecker {

    /** @return true when an active account with this user id exists */
    boolean isActive(String userId);
}
