package com.apac.claims.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.apac.claims.support.AbstractApiTest;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The error contract end to end. The test-only controllers (active only under this profile) throw each exception
 * type, so the real exception handler, the real filters and the real JSON serialisation are all exercised.
 */
@ActiveProfiles("error-contract-test")
class ErrorContractApiTest extends AbstractApiTest {

    private static final String BASE = "/api/v1/_test";

    private String token;

    private ResultActions callAsManager(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        if (token == null) {
            token = managerToken();
        }
        return mvc.perform(request.header("Authorization", bearer(token)));
    }

    private ResultActions throwing(String type) throws Exception {
        return callAsManager(get(BASE + "/throw/" + type));
    }

    @ParameterizedTest
    @CsvSource({
            "resource-not-found, 404, RESOURCE_NOT_FOUND",
            "duplicate-resource, 409, DUPLICATE_RESOURCE",
            "conflict, 409, CLAIM_INVALID_TRANSITION",
            "business-rule, 422, APPROVAL_LIMIT_EXCEEDED",
            "validation, 400, VALIDATION_FAILED",
            "upstream, 502, PAYMENT_GATEWAY_UNAVAILABLE",
            "unauthenticated, 401, UNAUTHENTICATED",
            "access-denied, 403, ACCESS_DENIED",
            "authentication, 401, UNAUTHENTICATED",
            "optimistic-lock, 409, CONCURRENT_MODIFICATION",
            "data-integrity, 409, DUPLICATE_RESOURCE",
            "unexpected, 500, INTERNAL_ERROR"
    })
    void eachExceptionTypeMapsToItsDocumentedStatusAndCode(String type, int status, String code) throws Exception {
        MvcResult result = throwing(type)
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value(BASE + "/throw/" + type))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // The timestamp is a real ISO instant, and the correlation id in the body is the one in the header.
        Instant timestamp = Instant.parse(JsonPath.read(body, "$.timestamp"));
        assertThat(timestamp).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(5));
        String correlationId = JsonPath.read(body, "$.correlationId");
        assertThat(correlationId).isNotBlank().isEqualTo(result.getResponse().getHeader("X-Correlation-Id"));
    }

    @Test
    void aValidationExceptionCarriesItsFieldErrors() throws Exception {
        throwing("validation")
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"))
                .andExpect(jsonPath("$.errors[1].field").value("count"));
    }

    @Test
    void errorsOtherThanValidationHaveNoErrorsProperty() throws Exception {
        throwing("conflict").andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void aDataIntegrityViolationNeverLeaksSqlOrConstraintNames() throws Exception {
        throwing("data-integrity")
                .andExpect(jsonPath("$.detail").value("The request conflicts with existing data"))
                .andExpect(content().string(not(containsString("insert into"))))
                .andExpect(content().string(not(containsString("uq_app_users"))))
                .andExpect(content().string(not(containsString("Unique index"))));
    }

    @Test
    void anUnexpectedExceptionGivesAGenericMessageAndNeverItsOwn() throws Exception {
        throwing("unexpected")
                .andExpect(jsonPath("$.detail").value("Unexpected error"))
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("at com.apac"))));
    }

    @Test
    void anAccessDeniedExceptionNeverLeaksItsInternalMessage() throws Exception {
        throwing("access-denied")
                .andExpect(jsonPath("$.detail").value("Access denied"))
                .andExpect(content().string(not(containsString("internal detail"))));
        throwing("authentication")
                .andExpect(jsonPath("$.detail").value("Authentication required"))
                .andExpect(content().string(not(containsString("internal detail"))));
    }

    @Test
    void aFailedPreAuthorizeIsA403NotA500() throws Exception {
        // The same path as any @PreAuthorize in the application: this is the case the catch-all would break.
        mvc.perform(get(BASE + "/manager-only").header("Authorization", bearer(claimantToken())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
        callAsManager(get(BASE + "/manager-only")).andExpect(status().isOk());
    }

    // ---- malformed and invalid requests -------------------------------------------------------------------

    @Test
    void malformedJsonIsA400WithTheMalformedRequestCodeAndDoesNotEchoTheBody() throws Exception {
        callAsManager(post(BASE + "/echo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"secret-token-123\", \"count\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(content().string(not(containsString("secret-token-123"))));
    }

    @Test
    void anInvalidEnumValueIsA400NamingTheFieldButNotTheValue() throws Exception {
        callAsManager(post(BASE + "/echo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"count\":1,\"kind\":\"Zebra\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Invalid value for field 'kind'"))
                .andExpect(content().string(not(containsString("Zebra"))));
    }

    @Test
    void aWrongTypeInTheBodyIsA400NamingTheField() throws Exception {
        callAsManager(post(BASE + "/echo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"count\":\"many\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Invalid value for field 'count'"));
    }

    @Test
    void beanValidationFailuresListEveryFieldSortedByName() throws Exception {
        callAsManager(post(BASE + "/echo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"count\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("Request validation failed"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("count"))
                .andExpect(jsonPath("$.errors[1].field").value("name"));
    }

    @Test
    void aValidBodyIsAccepted() throws Exception {
        callAsManager(post(BASE + "/echo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"count\":2,\"kind\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("A"));
    }

    @Test
    void aMissingRequiredParameterIsA400NamingIt() throws Exception {
        callAsManager(get(BASE + "/needs-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("x"))
                .andExpect(jsonPath("$.errors[0].message").value("is required"));
    }

    @Test
    void aParameterOfTheWrongTypeIsA400NamingIt() throws Exception {
        callAsManager(get(BASE + "/number/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("id"));
    }

    @Test
    void aConstraintViolationOnAValidatedControllerIsA400() throws Exception {
        callAsManager(get(BASE + "/constraint").param("n", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("n"));
        callAsManager(get(BASE + "/constraint").param("n", "5")).andExpect(status().isOk());
    }

    @Test
    void aMethodValidationFailureWithoutValidatedIsA400Too() throws Exception {
        callAsManager(get(BASE + "/method-validation").param("n", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("n"));
        callAsManager(get(BASE + "/method-validation").param("n", "5")).andExpect(status().isOk());
    }

    // ---- Spring's own errors ------------------------------------------------------------------------------

    @Test
    void anUnsupportedMethodIsA405WithTheAllowHeader() throws Exception {
        callAsManager(post(BASE + "/only-get"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void anUnsupportedMediaTypeIsA415() throws Exception {
        callAsManager(post(BASE + "/echo").contentType(MediaType.TEXT_PLAIN).content("name=x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void anUnknownPathForAnAuthenticatedCallerIsA404WithTheResourceNotFoundCode() throws Exception {
        callAsManager(get(BASE + "/nothing-lives-here"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    // ---- correlation id -----------------------------------------------------------------------------------

    @Test
    void aSuppliedCorrelationIdIsEchoedInTheHeaderAndTheErrorBody() throws Exception {
        callAsManager(get(BASE + "/throw/conflict").header("X-Correlation-Id", "client-trace-42"))
                .andExpect(header().string("X-Correlation-Id", "client-trace-42"))
                .andExpect(jsonPath("$.correlationId").value("client-trace-42"));
    }

    @Test
    void aMissingCorrelationIdIsGeneratedAsAUuid() throws Exception {
        MvcResult result = throwing("conflict").andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id"))
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void anInvalidCorrelationIdIsReplacedAndNeverReflectedBack() throws Exception {
        MvcResult result = callAsManager(get(BASE + "/throw/conflict").header("X-Correlation-Id", "bad id<script>"))
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).doesNotContain("script").doesNotContain(" ");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("script");
    }

    @Test
    void aSuccessfulResponseAlsoCarriesTheCorrelationId() throws Exception {
        callAsManager(get(BASE + "/only-get"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void twoRequestsGetTwoDifferentGeneratedCorrelationIds() throws Exception {
        String first = throwing("conflict").andReturn().getResponse().getHeader("X-Correlation-Id");
        String second = throwing("conflict").andReturn().getResponse().getHeader("X-Correlation-Id");

        assertThat(first).isNotEqualTo(second);
    }
}
