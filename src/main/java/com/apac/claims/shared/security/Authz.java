package com.apac.claims.shared.security;

/**
 * Expressions for {@code @PreAuthorize}, kept here so that feature modules can protect their controllers without
 * depending on the security module. They are compile-time constants because annotations require that.
 */
public final class Authz {

    public static final String CLAIMANT = "hasRole('CLAIMANT')";
    public static final String OFFICER = "hasRole('OFFICER')";
    public static final String MANAGER = "hasRole('MANAGER')";
    public static final String STAFF = "hasAnyRole('OFFICER','MANAGER')";
    public static final String AUTHENTICATED = "isAuthenticated()";

    private Authz() {
    }
}
