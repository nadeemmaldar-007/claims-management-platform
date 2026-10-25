package com.apac.claims.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import javax.crypto.spec.SecretKeySpec;

import com.apac.claims.config.JwtProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The decoder is the gatekeeper of the whole API, so every way of presenting a bad token is tried here. The secret
 * is 64 bytes long so that the same key can also sign an HS512 token, which isolates the algorithm check from the
 * signature check.
 */
class JwtConfigTest {

    private static final String SECRET = "0123456789abcdefghijklmnopqrstuv0123456789abcdefghijklmnopqrstuv";
    private static final String ISSUER = "claims-management-platform";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final JwtConfig config = new JwtConfig();
    private final JwtProperties properties = new JwtProperties(SECRET, 120, ISSUER);

    private JwtDecoder decoderAt(Instant now) {
        return config.jwtDecoder(properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private String validToken() {
        return new TokenIssuer(config.jwtEncoder(properties), properties, Clock.fixed(NOW, ZoneOffset.UTC))
                .issue("claimant.in", "CLAIMANT", "IN").token();
    }

    private static String encode(JwtEncoder encoder, MacAlgorithm algorithm, String issuer, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject("claimant.in")
                .issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(7200)).claim("role", "MANAGER").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(algorithm).build(), claims)).getTokenValue();
    }

    @Test
    void aValidTokenIsAccepted() {
        Jwt jwt = decoderAt(NOW).decode(validToken());

        assertThat(jwt.getSubject()).isEqualTo("claimant.in");
    }

    @Test
    void aTamperedPayloadIsRejectedBecauseTheSignatureNoLongerMatches() {
        String[] parts = validToken().split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"" + ISSUER + "\",\"sub\":\"claimant.in\",\"role\":\"MANAGER\","
                        + "\"iat\":" + NOW.getEpochSecond() + ",\"exp\":" + NOW.plusSeconds(7200).getEpochSecond() + "}")
                        .getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> decoderAt(NOW).decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void aDamagedSignatureIsRejected() {
        String token = validToken();
        String damaged = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");

        assertThatThrownBy(() -> decoderAt(NOW).decode(damaged)).isInstanceOf(JwtException.class);
    }

    @Test
    void anExpiredTokenIsRejected() {
        Instant threeHoursLater = NOW.plus(Duration.ofHours(3));

        assertThatThrownBy(() -> decoderAt(threeHoursLater).decode(validToken()))
                .isInstanceOf(JwtException.class).hasMessageContaining("expired");
    }

    @Test
    void aTokenJustPastItsExpiryIsStillAcceptedInsideTheSixtySecondClockSkew() {
        Instant thirtySecondsAfterExpiry = NOW.plus(Duration.ofMinutes(120)).plusSeconds(30);

        assertThatCode(() -> decoderAt(thirtySecondsAfterExpiry).decode(validToken())).doesNotThrowAnyException();
    }

    @Test
    void aTokenMoreThanSixtySecondsPastItsExpiryIsRejected() {
        Instant sixtyOneSecondsAfterExpiry = NOW.plus(Duration.ofMinutes(120)).plusSeconds(61);

        assertThatThrownBy(() -> decoderAt(sixtyOneSecondsAfterExpiry).decode(validToken()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        JwtProperties other = new JwtProperties("another-secret-0123456789abcdefghijklmnopqrstuvwxyz-xx", 120, ISSUER);
        String foreign = encode(config.jwtEncoder(other), MacAlgorithm.HS256, ISSUER, NOW);

        assertThatThrownBy(() -> decoderAt(NOW).decode(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenWithADifferentAlgorithmHeaderIsRejectedEvenWithTheRightKey() {
        // Same secret, but the header says HS512: only the algorithm check can reject this token.
        JwtEncoder hs512Encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512")));
        String hs512Token = encode(hs512Encoder, MacAlgorithm.HS512, ISSUER, NOW);

        assertThatThrownBy(() -> decoderAt(NOW).decode(hs512Token)).isInstanceOf(JwtException.class);
    }

    @Test
    void anUnsignedTokenWithAlgorithmNoneIsRejected() {
        String unsigned = new PlainJWT(new JWTClaimsSet.Builder().issuer(ISSUER).subject("claimant.in")
                .claim("role", "MANAGER").expirationTime(Date.from(NOW.plusSeconds(7200))).build()).serialize();

        assertThatThrownBy(() -> decoderAt(NOW).decode(unsigned)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenFromAnotherIssuerIsRejectedEvenWithTheRightKey() {
        String wrongIssuer = encode(config.jwtEncoder(properties), MacAlgorithm.HS256, "someone-else", NOW);

        assertThatThrownBy(() -> decoderAt(NOW).decode(wrongIssuer)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> decoderAt(NOW).decode("not-a-jwt")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoderAt(NOW).decode("a.b.c")).isInstanceOf(JwtException.class);
    }

    @Test
    void aSecretShorterThan32BytesStopsTheEncoderFromBeingCreated() {
        JwtProperties weak = new JwtProperties("short-secret-31-bytes-long-xxxx", 120, ISSUER);
        assertThat(weak.secret().getBytes(StandardCharsets.UTF_8)).hasSize(31);

        assertThatThrownBy(() -> config.jwtEncoder(weak))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes")
                .hasMessageContaining("31 bytes")
                .hasMessageNotContaining(weak.secret());
    }

    @Test
    void aSecretShorterThan32BytesStopsTheDecoderFromBeingCreated() {
        JwtProperties weak = new JwtProperties("tiny", 120, ISSUER);

        assertThatThrownBy(() -> config.jwtDecoder(weak, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void aSecretOfExactly32BytesIsAccepted() {
        JwtProperties exact = new JwtProperties("0123456789abcdef0123456789abcdef", 120, ISSUER);

        assertThatCode(() -> config.jwtEncoder(exact)).doesNotThrowAnyException();
        assertThatCode(() -> config.jwtDecoder(exact, Clock.systemUTC())).doesNotThrowAnyException();
    }

    @Test
    void theLengthIsCountedInBytesNotCharacters() {
        // Sixteen two-byte characters are 32 bytes, so they are long enough although the string has 16 characters.
        JwtProperties multiByte = new JwtProperties("é".repeat(16), 120, ISSUER);

        assertThatCode(() -> config.jwtEncoder(multiByte)).doesNotThrowAnyException();
    }

    @Test
    void aMissingSecretIsReportedClearly() {
        JwtProperties missing = new JwtProperties(null, 120, ISSUER);

        assertThatThrownBy(() -> config.jwtEncoder(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.jwt.secret is not set");
    }
}
