package com.apac.claims.identity.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractApiTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class LoginApiTest extends AbstractApiTest {

    private static final String GENERIC_MESSAGE = "Invalid user ID, password or role";

    private ResultActions login(String role, Object body) throws Exception {
        var request = post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json(body));
        if (role != null) {
            request = request.param("role", role);
        }
        return mvc.perform(request);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "claimant.in, Claimant@123, CLAIMANT, IN",
            "officer.in, Officer@123, OFFICER, IN",
            "manager.apac, Manager@123, MANAGER, NONE"
    }, nullValues = "NONE")
    void everyRoleCanLogInWithItsSeededAccount(String userId, String password, Role role, String marketCode)
            throws Exception {
        MvcResult result = login(role.name(), Map.of("userId", userId, "password", password))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(7200))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.userId").value(userId))
                .andExpect(jsonPath("$.user.role").value(role.name()))
                .andExpect(marketCode == null
                        ? jsonPath("$.user.marketCode").value(nullValue())
                        : jsonPath("$.user.marketCode").value(marketCode))
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn();

        String token = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.role").value(role.name()));
    }

    @Test
    void theResponseNeverContainsThePasswordOrItsHash() throws Exception {
        MvcResult result = login("CLAIMANT", Map.of("userId", SEEDED_CLAIMANT, "password", CLAIMANT_PASSWORD))
                .andExpect(status().isOk()).andReturn();

        String body = result.getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain(CLAIMANT_PASSWORD).doesNotContain("$2a$")
                .doesNotContain("passwordHash");
    }

    @Test
    void aWrongPasswordIsRejected() throws Exception {
        login("CLAIMANT", Map.of("userId", SEEDED_CLAIMANT, "password", "Wrong@12345"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail").value(GENERIC_MESSAGE))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void theRightPasswordWithTheWrongRoleIsRejected() throws Exception {
        login("OFFICER", Map.of("userId", SEEDED_CLAIMANT, "password", CLAIMANT_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        login("MANAGER", Map.of("userId", SEEDED_OFFICER, "password", OFFICER_PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownUserIsRejected() throws Exception {
        login("CLAIMANT", Map.of("userId", uniqueUserId("nobody"), "password", "Whatever@123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anInactiveAccountIsRejectedEvenWithTheRightPasswordAndRole() throws Exception {
        AppUser inactive = createUser(Role.CLAIMANT, "IN", "Claimant@123", false);

        login("CLAIMANT", Map.of("userId", inactive.getUserId(), "password", "Claimant@123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(GENERIC_MESSAGE));
    }

    @Test
    void anAccountThatIsDeactivatedAfterwardsCanNoLongerLogIn() throws Exception {
        AppUser account = createUser(Role.CLAIMANT, "IN", "Claimant@123", true);
        login("CLAIMANT", Map.of("userId", account.getUserId(), "password", "Claimant@123"))
                .andExpect(status().isOk());

        account.setActive(false);
        users.saveAndFlush(account);

        login("CLAIMANT", Map.of("userId", account.getUserId(), "password", "Claimant@123"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everyKindOfFailureGivesExactlyTheSameMessageStatusAndCode() throws Exception {
        AppUser inactive = createUser(Role.OFFICER, "IN", "Officer@123", false);
        List<ResultActions> failures = List.of(
                login("CLAIMANT", Map.of("userId", SEEDED_CLAIMANT, "password", "Wrong@12345")),
                login("OFFICER", Map.of("userId", SEEDED_CLAIMANT, "password", CLAIMANT_PASSWORD)),
                login("CLAIMANT", Map.of("userId", uniqueUserId("ghost"), "password", "Wrong@12345")),
                login("OFFICER", Map.of("userId", inactive.getUserId(), "password", "Officer@123")));

        for (ResultActions failure : failures) {
            failure.andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.title").value("Unauthorized"))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.detail").value(GENERIC_MESSAGE));
        }
    }

    @Test
    void theUserIdIsNotCaseSensitiveAndSurroundingSpacesAreIgnored() throws Exception {
        login("CLAIMANT", Map.of("userId", "CLAIMANT.IN", "password", CLAIMANT_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").value("claimant.in"));
        login("CLAIMANT", Map.of("userId", "  Claimant.In ", "password", CLAIMANT_PASSWORD))
                .andExpect(status().isOk());
    }

    @Test
    void thePasswordIsCaseSensitive() throws Exception {
        login("CLAIMANT", Map.of("userId", SEEDED_CLAIMANT, "password", CLAIMANT_PASSWORD.toLowerCase()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMissingRoleIsABadRequestNamingTheParameter() throws Exception {
        login(null, Map.of("userId", SEEDED_CLAIMANT, "password", CLAIMANT_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("role"));
    }

    @ParameterizedTest
    @CsvSource({"ADMIN", "claimant", "''", "SUPERUSER"})
    void anUnknownRoleValueIsABadRequestListingTheAllowedValues(String role) throws Exception {
        login(role, Map.of("userId", SEEDED_CLAIMANT, "password", CLAIMANT_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("role"));
    }

    @Test
    void theAllowedRolesAreListedInTheErrorMessage() throws Exception {
        login("ADMIN", Map.of("userId", SEEDED_CLAIMANT, "password", CLAIMANT_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("must be one of CLAIMANT, OFFICER, MANAGER"));
    }

    @Test
    void aBlankUserIdOrPasswordIsABadRequest() throws Exception {
        login("CLAIMANT", Map.of("userId", "", "password", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'userId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
    }

    @Test
    void aMissingBodyIsAMalformedRequest() throws Exception {
        mvc.perform(post("/api/v1/auth/login").param("role", "CLAIMANT").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void aFailedLoginDoesNotEchoThePasswordBack() throws Exception {
        login("CLAIMANT", Map.of("userId", SEEDED_CLAIMANT, "password", "Wrong@Secret99"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("Wrong@Secret99"))));
    }
}
