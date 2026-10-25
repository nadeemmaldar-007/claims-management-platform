package com.apac.claims.security;

/**
 * An access token together with its lifetime, as returned by {@link TokenIssuer}.
 *
 * @param token the signed JWT
 * @param expiresInSeconds seconds from issuing until the token expires
 */
public record IssuedToken(String token, long expiresInSeconds) {
}
