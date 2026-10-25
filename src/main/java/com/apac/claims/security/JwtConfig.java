package com.apac.claims.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.apac.claims.config.JwtProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Creates the JWT encoder and decoder from one shared secret (HS256). The decoder is told explicitly to expect
 * HS256, because its default is RS256, and it validates the issuer and the timestamps. A secret that is too short
 * stops the application at startup rather than producing weak tokens.
 */
@Configuration
public class JwtConfig {

    /** HS256 needs a key of at least 256 bits. */
    static final int MIN_SECRET_BYTES = 32;

    @Bean
    public JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties.secret())));
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties.secret()))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(tokenValidator(properties.issuer(), clock));
        return decoder;
    }

    /**
     * The issuer must match and the token must be within its validity period. The timestamp check keeps Spring's
     * default tolerance of 60 seconds for clock drift and reads the time from the {@link Clock} bean.
     */
    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(issuer));
    }

    static SecretKey secretKey(String secret) {
        if (secret == null) {
            throw new IllegalStateException("app.security.jwt.secret is not set");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            // Only the length is reported, never the secret itself.
            throw new IllegalStateException("app.security.jwt.secret must be at least " + MIN_SECRET_BYTES
                    + " bytes long for HS256, but it is " + bytes.length + " bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
