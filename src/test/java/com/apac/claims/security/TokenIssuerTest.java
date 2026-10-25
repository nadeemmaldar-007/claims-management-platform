package com.apac.claims.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import com.apac.claims.config.JwtProperties;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class TokenIssuerTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef-0123456789abcdef";
    private static final String ISSUER = "claims-management-platform";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final JwtConfig config = new JwtConfig();

    private TokenIssuer issuerAt(Instant now, long expiryMinutes) {
        JwtProperties properties = new JwtProperties(SECRET, expiryMinutes, ISSUER);
        return new TokenIssuer(config.jwtEncoder(properties), properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** Decodes with a clock at the same instant, so validation never interferes with what is being inspected. */
    private Jwt decode(String token, Instant now) {
        JwtProperties properties = new JwtProperties(SECRET, 120, ISSUER);
        JwtDecoder decoder = config.jwtDecoder(properties, Clock.fixed(now, ZoneOffset.UTC));
        return decoder.decode(token);
    }

    @Test
    void theHeaderNamesHs256ExplicitlyAndNothingElse() {
        String token = issuerAt(NOW, 120).issue("claimant.in", "CLAIMANT", "IN").token();

        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);

        assertThat(header).contains("\"alg\":\"HS256\"");
        assertThat(decode(token, NOW).getHeaders()).containsEntry("alg", "HS256");
    }

    @Test
    void claimsCarryIssuerSubjectRoleAndMarket() {
        String token = issuerAt(NOW, 120).issue("officer.sg", "OFFICER", "SG").token();

        Jwt jwt = decode(token, NOW);

        // getIssuer() would try to turn the claim into a URL, which our plain-text issuer is not.
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(jwt.getSubject()).isEqualTo("officer.sg");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("OFFICER");
        assertThat(jwt.getClaimAsString("market")).isEqualTo("SG");
    }

    @Test
    void issuedAtAndExpiryComeFromTheClockAndTheConfiguredMinutes() {
        IssuedToken issued = issuerAt(NOW, 120).issue("claimant.in", "CLAIMANT", "IN");

        Jwt jwt = decode(issued.token(), NOW);

        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(120)));
        assertThat(issued.expiresInSeconds()).isEqualTo(7200L);
    }

    @Test
    void theLifetimeFollowsTheConfiguredExpiry() {
        IssuedToken issued = issuerAt(NOW, 15).issue("claimant.in", "CLAIMANT", "IN");

        assertThat(issued.expiresInSeconds()).isEqualTo(900L);
        assertThat(decode(issued.token(), NOW).getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    void aLaterClockGivesALaterExpiry() {
        Instant later = NOW.plus(Duration.ofDays(1));

        Jwt jwt = decode(issuerAt(later, 120).issue("claimant.in", "CLAIMANT", "IN").token(), later);

        assertThat(jwt.getIssuedAt()).isEqualTo(later);
        assertThat(jwt.getExpiresAt()).isEqualTo(later.plus(Duration.ofMinutes(120)));
    }

    @Test
    void theMarketClaimIsLeftOutForAManagerWhoHasNone() {
        String token = issuerAt(NOW, 120).issue("manager.apac", "MANAGER", null).token();

        Jwt jwt = decode(token, NOW);

        assertThat(jwt.getClaims()).doesNotContainKey("market");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("MANAGER");
    }

    @Test
    void everyTokenHasTheThreePartsOfASignedJwt() {
        String token = issuerAt(NOW, 120).issue("claimant.in", "CLAIMANT", "IN").token();

        assertThat(token.split("\\.")).hasSize(3).allSatisfy(part -> assertThat(part).isNotBlank());
    }
}
