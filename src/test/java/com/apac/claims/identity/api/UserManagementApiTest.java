package com.apac.claims.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractApiTest;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class UserManagementApiTest extends AbstractApiTest {

    private Map<String, Object> staffBody(String userId, String role, String marketCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("password", "Officer@123");
        body.put("fullName", "Staff Member");
        body.put("email", userId + "@test.example");
        body.put("role", role);
        if (marketCode != null) {
            body.put("marketCode", marketCode);
        }
        return body;
    }

    private ResultActions create(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/users").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions list(String token, String query) throws Exception {
        return mvc.perform(get("/api/v1/users" + query).header("Authorization", bearer(token)));
    }

    // ---- creating staff -----------------------------------------------------------------------------------

    @Test
    void aManagerCreatesAnOfficerWhoCanThenLogInAsAnOfficer() throws Exception {
        String userId = uniqueUserId("off");

        create(managerToken(), staffBody(userId, "OFFICER", "IN"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.role").value("OFFICER"))
                .andExpect(jsonPath("$.marketCode").value("IN"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString("Officer@123"))));

        String token = login(userId, "Officer@123", Role.OFFICER);
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OFFICER"))
                .andExpect(jsonPath("$.marketCode").value("IN"));
    }

    @Test
    void aManagerCreatesAnotherManagerWithoutAMarket() throws Exception {
        String userId = uniqueUserId("mgr");

        create(managerToken(), staffBody(userId, "MANAGER", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.marketCode").value(org.hamcrest.Matchers.nullValue()));

        assertThat(login(userId, "Officer@123", Role.MANAGER)).isNotBlank();
    }

    @Test
    void anOfficerWithoutAMarketIsABadRequestNamingMarketCode() throws Exception {
        create(managerToken(), staffBody(uniqueUserId("off"), "OFFICER", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("marketCode"));
    }

    @Test
    void aManagerWithAMarketIsABadRequestNamingMarketCode() throws Exception {
        create(managerToken(), staffBody(uniqueUserId("mgr"), "MANAGER", "IN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("marketCode"));
    }

    @Test
    void anOfficerForAnUnknownMarketIsABadRequest() throws Exception {
        create(managerToken(), staffBody(uniqueUserId("off"), "OFFICER", "ZZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("marketCode"));
    }

    @Test
    void aClaimantCannotBeCreatedThroughThisEndpoint() throws Exception {
        create(managerToken(), staffBody(uniqueUserId("clm"), "CLAIMANT", "IN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("role"));
    }

    @Test
    void aRoleThatDoesNotExistIsAMalformedRequestNamingTheField() throws Exception {
        create(managerToken(), staffBody(uniqueUserId("adm"), "ADMIN", "IN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Invalid value for field 'role'"))
                .andExpect(content().string(not(containsString("ADMIN"))));
    }

    @Test
    void aMissingRoleIsABadRequest() throws Exception {
        Map<String, Object> body = staffBody(uniqueUserId("nor"), "OFFICER", "IN");
        body.remove("role");

        create(managerToken(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'role')]").exists());
    }

    @Test
    void aDuplicateUserIdIsAConflict() throws Exception {
        String userId = uniqueUserId("dup");
        String token = managerToken();
        create(token, staffBody(userId, "OFFICER", "IN")).andExpect(status().isCreated());

        Map<String, Object> again = staffBody(userId, "OFFICER", "IN");
        again.put("email", "other-" + userId + "@test.example");
        create(token, again).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void aWeakPasswordIsRejectedForStaffToo() throws Exception {
        Map<String, Object> body = staffBody(uniqueUserId("weak"), "OFFICER", "IN");
        body.put("password", "weak");

        create(managerToken(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
    }

    // ---- listing ------------------------------------------------------------------------------------------

    @Test
    void theListIsPagedAndFilteredByRoleAndMarket() throws Exception {
        String token = managerToken();
        List<String> indian = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = uniqueUserId("lst");
            create(token, staffBody(id, "OFFICER", "IN")).andExpect(status().isCreated());
            indian.add(id);
        }
        String singaporean = uniqueUserId("lst");
        create(token, staffBody(singaporean, "OFFICER", "SG")).andExpect(status().isCreated());

        MvcResult all = list(token, "?role=OFFICER&marketCode=IN&size=50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].role").exists())
                .andReturn();

        List<String> ids = JsonPath.read(all.getResponse().getContentAsString(), "$.content[*].userId");
        List<String> roles = JsonPath.read(all.getResponse().getContentAsString(), "$.content[*].role");
        List<String> markets = JsonPath.read(all.getResponse().getContentAsString(), "$.content[*].marketCode");
        assertThat(ids).containsAll(indian).doesNotContain(singaporean);
        assertThat(roles).containsOnly("OFFICER");
        assertThat(markets).containsOnly("IN");
    }

    @Test
    void thePageEnvelopeHasTheDocumentedFields() throws Exception {
        list(managerToken(), "?size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(13)))
                .andExpect(jsonPath("$.totalPages").isNumber())
                .andExpect(jsonPath("$.last").value(false));
    }

    @Test
    void theSecondPageContinuesWhereTheFirstEnded() throws Exception {
        String token = managerToken();
        MvcResult first = list(token, "?size=2&page=0&sort=userId,asc").andReturn();
        MvcResult second = list(token, "?size=2&page=1&sort=userId,asc").andReturn();

        List<String> firstIds = JsonPath.read(first.getResponse().getContentAsString(), "$.content[*].userId");
        List<String> secondIds = JsonPath.read(second.getResponse().getContentAsString(), "$.content[*].userId");

        assertThat(firstIds).hasSize(2);
        assertThat(secondIds).hasSize(2).doesNotContainAnyElementsOf(firstIds);
        assertThat(secondIds.get(0).compareTo(firstIds.get(1))).isPositive();
    }

    @Test
    void aSizeAboveFiftyIsCappedAtFifty() throws Exception {
        list(managerToken(), "?size=500")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void theDefaultSizeIsTenAndTheDefaultOrderIsByUserId() throws Exception {
        MvcResult result = list(managerToken(), "").andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(10)).andReturn();

        List<String> ids = JsonPath.read(result.getResponse().getContentAsString(), "$.content[*].userId");
        assertThat(ids).isSortedAccordingTo(String::compareTo);
    }

    @Test
    void sortingByCreatedAtDescendingPutsTheNewestFirst() throws Exception {
        String token = managerToken();
        String older = uniqueUserId("old");
        create(token, staffBody(older, "OFFICER", "AU")).andExpect(status().isCreated());
        String newer = uniqueUserId("new");
        create(token, staffBody(newer, "OFFICER", "AU")).andExpect(status().isCreated());

        MvcResult result = list(token, "?role=OFFICER&marketCode=AU&sort=createdAt,desc&size=50").andReturn();

        List<String> ids = JsonPath.read(result.getResponse().getContentAsString(), "$.content[*].userId");
        assertThat(ids.indexOf(newer)).isNotNegative().isLessThan(ids.indexOf(older));
    }

    @ParameterizedTest
    @CsvSource({
            "?size=0, size",
            "?size=-3, size",
            "?page=-1, page",
            "?sort=password, sort",
            "?sort=userId;drop, sort",
            "'?sort=userId,sideways', sort"
    })
    void anInvalidPagingOrSortParameterIsABadRequestNamingIt(String query, String field) throws Exception {
        list(managerToken(), query)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    @Test
    void anUnknownRoleFilterIsABadRequest() throws Exception {
        list(managerToken(), "?role=ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("role"));
    }

    @Test
    void aMarketFilterIsNotCaseSensitive() throws Exception {
        MvcResult lower = list(managerToken(), "?role=CLAIMANT&marketCode=jp&size=50").andReturn();
        List<String> markets = JsonPath.read(lower.getResponse().getContentAsString(), "$.content[*].marketCode");

        assertThat(markets).isNotEmpty().containsOnly("JP");
    }

    @Test
    void theListNeverContainsPasswordHashes() throws Exception {
        list(managerToken(), "?size=50")
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("$2a$"))))
                .andExpect(content().string(not(containsString("passwordHash"))));
    }
}
