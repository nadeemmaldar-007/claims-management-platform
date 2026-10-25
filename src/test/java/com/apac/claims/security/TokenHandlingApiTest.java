package com.apac.claims.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractApiTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** What the API does with each kind of bad or stale token. Every case must be a JSON 401, never a 403 or a 500. */
class TokenHandlingApiTest extends AbstractApiTest {

    private ResultActions callWith(String path, String authorization) throws Exception {
        var request = get(path);
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return mvc.perform(request);
    }

    private void expectJson401(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void noTokenGivesAJsonProblemWithCodeAndCorrelationId() throws Exception {
        MvcResult result = callWith("/api/v1/users/me", null).andReturn();

        expectJson401(callWith("/api/v1/users/me", null));
        String headerValue = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(headerValue).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).contains(headerValue);
    }

    @Test
    void theCorrelationIdHeaderIsPresentEvenOnTheSecurityFiltersOwn401() throws Exception {
        callWith("/api/v1/markets", null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void aSuppliedCorrelationIdIsEchoedOnAn401() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("X-Correlation-Id", "trace-from-client-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", "trace-from-client-1"))
                .andExpect(jsonPath("$.correlationId").value("trace-from-client-1"));
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        String token = claimantToken();
        String tampered = token.substring(0, token.length() - 3) + (token.endsWith("abc") ? "xyz" : "abc");

        expectJson401(callWith("/api/v1/users/me", bearer(tampered)));
    }

    @Test
    void aTokenWithAForgedRoleClaimIsRejectedBecauseTheSignatureNoLongerMatches() throws Exception {
        String[] parts = claimantToken().split("\\.");
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"claims-management-platform\",\"sub\":\"claimant.in\",\"role\":\"MANAGER\","
                        + "\"iat\":1,\"exp\":4102444800}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        expectJson401(callWith("/api/v1/users", bearer(parts[0] + "." + forged + "." + parts[2])));
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        Instant now = Instant.now();
        String expired = tokenFor(SEEDED_CLAIMANT, "CLAIMANT", "IN", now.minus(Duration.ofHours(3)),
                now.minus(Duration.ofHours(1)), jwtProperties.issuer());

        expectJson401(callWith("/api/v1/users/me", bearer(expired)));
    }

    @Test
    void aValidlySignedTokenFromAnotherIssuerIsRejected() throws Exception {
        Instant now = Instant.now();
        String foreign = tokenFor(SEEDED_CLAIMANT, "CLAIMANT", "IN", now, now.plus(Duration.ofHours(1)),
                "some-other-issuer");

        expectJson401(callWith("/api/v1/users/me", bearer(foreign)));
    }

    @Test
    void aFreshTokenForAUserWhoDoesNotExistIsRejected() throws Exception {
        Instant now = Instant.now();
        String ghost = tokenFor(uniqueUserId("ghost"), "CLAIMANT", "IN", now, now.plus(Duration.ofHours(1)),
                jwtProperties.issuer());

        expectJson401(callWith("/api/v1/users/me", bearer(ghost)));
        expectJson401(callWith("/api/v1/markets", bearer(ghost)));
    }

    @Test
    void theTokenOfAUserWhoWasDeletedStopsWorkingOnEveryProtectedEndpoint() throws Exception {
        AppUser account = createUser(Role.CLAIMANT, "IN", "Claimant@123", true);
        String token = login(account.getUserId(), "Claimant@123", Role.CLAIMANT);
        callWith("/api/v1/users/me", bearer(token)).andExpect(status().isOk());
        callWith("/api/v1/markets", bearer(token)).andExpect(status().isOk());

        users.delete(account);
        users.flush();

        expectJson401(callWith("/api/v1/users/me", bearer(token)));
        // /markets does not look at the current user itself, so this proves the check sits in the security chain.
        expectJson401(callWith("/api/v1/markets", bearer(token)));
    }

    @Test
    void theTokenOfAUserWhoWasDeactivatedStopsWorkingImmediately() throws Exception {
        AppUser account = createUser(Role.OFFICER, "IN", "Officer@123", true);
        String token = login(account.getUserId(), "Officer@123", Role.OFFICER);
        callWith("/api/v1/markets", bearer(token)).andExpect(status().isOk());

        account.setActive(false);
        users.saveAndFlush(account);

        expectJson401(callWith("/api/v1/markets", bearer(token)));
        expectJson401(callWith("/api/v1/users/me", bearer(token)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer ", "Bearer not-a-jwt", "Bearer a.b.c", "Basic Y2xhaW1hbnQuaW46Q2xhaW1hbnRAMTIz",
            "bearer", "Token abc"})
    void malformedOrForeignAuthorizationHeadersAreRejected(String authorization) throws Exception {
        expectJson401(callWith("/api/v1/users/me", authorization));
    }

    @Test
    void aValidTokenStillWorks() throws Exception {
        callWith("/api/v1/users/me", bearer(claimantToken()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
