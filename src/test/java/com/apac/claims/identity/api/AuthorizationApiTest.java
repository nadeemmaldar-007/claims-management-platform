package com.apac.claims.identity.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractApiTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Role checks on the manager-only endpoints. A forbidden call must be a JSON 403 with code ACCESS_DENIED: if the
 * access-denied exception were not handled explicitly, the catch-all handler would turn it into a 500.
 */
class AuthorizationApiTest extends AbstractApiTest {

    private String tokenFor(Role role) throws Exception {
        return switch (role) {
            case CLAIMANT -> claimantToken();
            case OFFICER -> officerToken();
            case MANAGER -> managerToken();
        };
    }

    private Map<String, Object> newOfficerBody() {
        String userId = uniqueUserId("authz");
        return Map.of("userId", userId, "password", "Officer@123", "fullName", "Authz Officer",
                "email", userId + "@test.example", "role", "OFFICER", "marketCode", "IN");
    }

    private void expectJsonForbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail").value("Access denied"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CLAIMANT", "OFFICER"})
    void aNonManagerCannotCreateUsersAndGetsAJson403NotA500(Role role) throws Exception {
        expectJsonForbidden(mvc.perform(post("/api/v1/users")
                .header("Authorization", bearer(tokenFor(role)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(newOfficerBody()))));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"CLAIMANT", "OFFICER"})
    void aNonManagerCannotListUsersAndGetsAJson403NotA500(Role role) throws Exception {
        expectJsonForbidden(mvc.perform(get("/api/v1/users").header("Authorization", bearer(tokenFor(role)))));
    }

    @Test
    void aManagerCanListUsers() throws Exception {
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(managerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void anAnonymousCallerGets401NotA403OnTheManagerEndpoints() throws Exception {
        mvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content(json(newOfficerBody())))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyRoleMayReadItsOwnProfile(Role role) throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(tokenFor(role))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(role.name()));
    }

    /**
     * Characterises a known property of the design, it is not a requirement: Spring MVC binds and validates the
     * request body before the method-security check of {@code @PreAuthorize} runs. A caller without the role who
     * sends an INVALID body therefore sees the validation error (400), while the same caller with a valid body is
     * refused (403, covered above). Only field names and messages are shown, which the public OpenAPI document
     * already lists. Getting 403 first would need the role rules duplicated in the filter chain.
     */
    @Test
    void anInvalidBodyFromACallerWithoutTheRoleIsReportedAsAValidationFailureNotAs403() throws Exception {
        mvc.perform(post("/api/v1/users")
                        .header("Authorization", bearer(claimantToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
