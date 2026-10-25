package com.apac.claims.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;

import com.apac.claims.support.AbstractApiTest;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** The API documentation, Swagger UI, the root redirect and the health endpoint: public, and correct. */
class OpenApiAndDocsApiTest extends AbstractApiTest {

    private String apiDocs() throws Exception {
        MvcResult result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString();
    }

    @Test
    void theApiDocsArePublicJsonWithTheTitleAndVersion() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.info.title").value("Claims Management Platform API"))
                .andExpect(jsonPath("$.info.version").value("1.0.0"));
    }

    @Test
    void theDocumentDeclaresTheBearerAuthSchemeAndRequiresItGlobally() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray());
    }

    @Test
    void theLoginRoleParameterIsARequiredQueryEnumOfExactlyTheThreeRoles() throws Exception {
        String docs = apiDocs();

        List<String> allowed = JsonPath.read(docs,
                "$.paths['/api/v1/auth/login'].post.parameters[?(@.name=='role')].schema.enum[*]");
        List<String> in = JsonPath.read(docs, "$.paths['/api/v1/auth/login'].post.parameters[?(@.name=='role')].in");
        List<Boolean> required = JsonPath.read(docs,
                "$.paths['/api/v1/auth/login'].post.parameters[?(@.name=='role')].required");

        assertThat(allowed).containsExactly("CLAIMANT", "OFFICER", "MANAGER");
        assertThat(in).containsExactly("query");
        assertThat(required).containsExactly(true);
    }

    @Test
    void theLoginOperationDescriptionIsExactlyAsSpecified() throws Exception {
        String description = JsonPath.read(apiDocs(), "$.paths['/api/v1/auth/login'].post.description");

        assertThat(description).isEqualTo("1) choose your role in the dropdown, 2) enter userId and password in the "
                + "body, 3) Execute, 4) copy accessToken, click Authorize, paste the token only.");
    }

    @Test
    void loginAndRegisterAreMarkedPublicWithAnEmptySecurityRequirement() throws Exception {
        String docs = apiDocs();

        List<Object> loginSecurity = JsonPath.read(docs, "$.paths['/api/v1/auth/login'].post.security");
        List<Object> registerSecurity = JsonPath.read(docs, "$.paths['/api/v1/auth/register'].post.security");

        assertThat(loginSecurity).isEmpty();
        assertThat(registerSecurity).isEmpty();
    }

    @Test
    void protectedOperationsInheritTheGlobalRequirementSoTheyShowTheLock() throws Exception {
        // No operation-level security element means the global bearerAuth requirement applies.
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/users/me'].get.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/markets'].get.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/users'].post.security").doesNotExist());
    }

    @Test
    void theDocumentContainsTheIdentityAndMarketEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/users'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/users'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/users/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/markets'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/markets/{code}'].get").exists());
    }

    @Test
    void theRootRedirectAndTheTestOnlyControllersAreNotInTheDocument() throws Exception {
        String docs = apiDocs();

        assertThat(JsonPath.<java.util.Map<String, Object>>read(docs, "$.paths")).doesNotContainKey("/")
                .allSatisfy((path, value) -> assertThat(path).doesNotContain("_test"));
    }

    @Test
    void everyTagIsListedExactlyOnceAndTheSixAreThere() throws Exception {
        List<String> tags = JsonPath.read(apiDocs(), "$.tags[*].name");

        assertThat(tags).doesNotHaveDuplicates()
                .contains("Authentication", "Users", "Markets", "Claims", "Notifications", "Reports");
        assertThat(new HashSet<>(tags)).hasSameSizeAs(tags);
    }

    @Test
    void theDescriptionExplainsLoginAndListsTheDemoUserIdsButNoPasswords() throws Exception {
        String description = JsonPath.read(apiDocs(), "$.info.description");

        assertThat(description).contains("role").contains("Authorize").contains("manager.apac")
                .contains("officer.in").contains("claimant.in")
                .doesNotContain("Manager@123").doesNotContain("Officer@123").doesNotContain("Claimant@123");
    }

    @Test
    void theApiDocsRequireNoToken() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
    }

    @Test
    void swaggerUiNeedsNoToken() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger")));
    }

    @Test
    void theSwaggerUiHtmlAddressRedirectsToTheUiWithoutAToken() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/swagger-ui/index.html")));
    }

    @Test
    void theRootAddressRedirectsToTheSwaggerUi() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
    }

    @Test
    void healthIsPublicAndReportsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/info", "/actuator", "/actuator/env", "/actuator/beans"})
    void otherActuatorEndpointsAreNotPublic(String path) throws Exception {
        // Only /actuator/health is public, so without a token every other actuator path is a 401, never a 200.
        int statusCode = mvc.perform(get(path)).andReturn().getResponse().getStatus();

        assertThat(statusCode).isEqualTo(401);
    }
}
