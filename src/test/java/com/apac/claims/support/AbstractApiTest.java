package com.apac.claims.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.apac.claims.config.JwtProperties;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.MarketRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base class of the API tests: the full application on H2 with the real security chain, driven through MockMvc.
 * Tokens always come from the real login endpoint, and every test creates its own users with unique ids, so no test
 * depends on rows left behind by another. All subclasses share one Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractApiTest {

    public static final String SEEDED_CLAIMANT = "claimant.in";
    public static final String SEEDED_OFFICER = "officer.in";
    public static final String SEEDED_MANAGER = "manager.apac";
    public static final String CLAIMANT_PASSWORD = "Claimant@123";
    public static final String OFFICER_PASSWORD = "Officer@123";
    public static final String MANAGER_PASSWORD = "Manager@123";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected UserRepository users;
    @Autowired
    protected MarketRepository markets;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected JwtEncoder jwtEncoder;
    @Autowired
    protected JwtProperties jwtProperties;

    /** A user id that is valid for registration (4 to 30 allowed characters) and unique in this test run. */
    public static String uniqueUserId(String prefix) {
        return prefix + "." + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    protected String json(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    /** Calls the real login endpoint and returns the raw result, whatever the status. */
    protected MvcResult attemptLogin(String userId, String password, Role role) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .param("role", role.name())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", userId, "password", password))))
                .andReturn();
    }

    /** Logs in through the real endpoint and returns the access token; fails the test if login does not succeed. */
    protected String login(String userId, String password, Role role) throws Exception {
        MvcResult result = attemptLogin(userId, password, role);
        assertThat(result.getResponse().getStatus()).as("login of %s as %s", userId, role).isEqualTo(200);
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    protected String claimantToken() throws Exception {
        return login(SEEDED_CLAIMANT, CLAIMANT_PASSWORD, Role.CLAIMANT);
    }

    protected String officerToken() throws Exception {
        return login(SEEDED_OFFICER, OFFICER_PASSWORD, Role.OFFICER);
    }

    protected String managerToken() throws Exception {
        return login(SEEDED_MANAGER, MANAGER_PASSWORD, Role.MANAGER);
    }

    /**
     * Inserts an account directly, for set-ups that the API deliberately cannot do (an inactive account) or that
     * need a known password without going through registration.
     */
    protected AppUser createUser(Role role, String marketCode, String rawPassword, boolean active) {
        String userId = uniqueUserId("t" + role.name().substring(0, 3).toLowerCase());
        AppUser user = new AppUser(userId, passwordEncoder.encode(rawPassword), "Test " + role, userId + "@test.example",
                role, marketCode == null ? null : markets.findByCode(marketCode).orElseThrow());
        user.setActive(active);
        return users.saveAndFlush(user);
    }

    /** Builds a token signed with the application's own key, with claims the test controls. */
    protected String tokenFor(String subject, String role, String market, Instant issuedAt, Instant expiresAt,
                              String issuer) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("role", role);
        if (market != null) {
            claims.claim("market", market);
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
