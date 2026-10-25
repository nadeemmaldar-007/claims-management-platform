package com.apac.claims.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.apac.claims.config.JwtProperties;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues access tokens. It takes plain values (user id, role name, market code) so that it does not depend on the
 * identity module. The header always names HS256 explicitly, and the {@code market} claim is only added when there
 * is one, because the claims builder rejects null values.
 */
@Component
public class TokenIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public TokenIssuer(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(String userId, String roleName, String marketCode) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(Duration.ofMinutes(properties.expiryMinutes()));
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("role", roleName);
        if (marketCode != null) {
            claims.claim("market", marketCode);
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new IssuedToken(token, Duration.between(issuedAt, expiresAt).toSeconds());
    }
}
