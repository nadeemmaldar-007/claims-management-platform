package com.apac.claims.claim.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.apac.claims.claim.api.dto.ApproveRequest;
import com.apac.claims.claim.api.dto.RecordAssessmentRequest;
import com.apac.claims.claim.api.dto.RejectRequest;
import com.apac.claims.claim.api.dto.SubmitClaimRequest;
import com.apac.claims.support.AbstractApiTest;
import com.jayway.jsonpath.JsonPath;

import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The generated OpenAPI document describes the claims and policies endpoints completely: every path and method, the
 * idempotency header, the named examples (which must be valid requests), the status codes and the tags.
 */
class ClaimsOpenApiTest extends AbstractApiTest {

    @Autowired
    private Validator validator;

    private String docs;

    private String apiDocs() throws Exception {
        if (docs == null) {
            docs = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString();
        }
        return docs;
    }

    static Stream<Arguments> operations() {
        return Stream.of(
                Arguments.of("/api/v1/claims", "post"),
                Arguments.of("/api/v1/claims", "get"),
                Arguments.of("/api/v1/claims/queue", "get"),
                Arguments.of("/api/v1/claims/{claimNumber}", "get"),
                Arguments.of("/api/v1/claims/{claimNumber}/timeline", "get"),
                Arguments.of("/api/v1/claims/{claimNumber}/pick-up", "post"),
                Arguments.of("/api/v1/claims/{claimNumber}/assign", "post"),
                Arguments.of("/api/v1/claims/{claimNumber}/escalate", "post"),
                Arguments.of("/api/v1/claims/{claimNumber}/approve", "post"),
                Arguments.of("/api/v1/claims/{claimNumber}/reject", "post"),
                Arguments.of("/api/v1/claims/{claimNumber}/assessments", "post"),
                Arguments.of("/api/v1/claims/{claimNumber}/assessments", "get"),
                Arguments.of("/api/v1/claims/{claimNumber}/information-requests", "post"),
                Arguments.of("/api/v1/claims/{claimNumber}/information-requests", "get"),
                Arguments.of("/api/v1/claims/{claimNumber}/information-requests/{requestId}/respond", "post"),
                Arguments.of("/api/v1/claims/{claimNumber}/information-requests/{requestId}/cancel", "post"),
                Arguments.of("/api/v1/policies/mine", "get"),
                Arguments.of("/api/v1/policies/{policyNumber}", "get"),
                Arguments.of("/api/v1/policies/{policyNumber}", "put"));
    }

    @ParameterizedTest(name = "{1} {0} is documented")
    @MethodSource("operations")
    void everyClaimAndPolicyOperationIsDocumentedWithASummaryAndResponses(String path, String method) throws Exception {
        String operation = "$.paths['" + path + "']." + method;

        String summary = JsonPath.read(apiDocs(), operation + ".summary");
        Map<String, Object> responses = JsonPath.read(apiDocs(), operation + ".responses");

        assertThat(summary).isNotBlank();
        assertThat(responses).containsKey("401");
        assertThat(responses.keySet().stream().anyMatch(code -> code.startsWith("2"))).isTrue();
    }

    @ParameterizedTest(name = "{1} {0} is tagged")
    @MethodSource("operations")
    void everyOperationBelongsToTheClaimsOrThePoliciesTag(String path, String method) throws Exception {
        List<String> tags = JsonPath.read(apiDocs(), "$.paths['" + path + "']." + method + ".tags");

        assertThat(tags).containsExactly(path.startsWith("/api/v1/policies") ? "Policies" : "Claims");
    }

    @Test
    void thePathsOfTheDocumentContainNoSettlementEndpointYet() throws Exception {
        Map<String, Object> paths = JsonPath.read(apiDocs(), "$.paths");

        assertThat(paths.keySet()).noneMatch(path -> path.contains("settle"));
    }

    @Test
    void theTagsClaimsAndPoliciesAreDeclaredWithADescription() throws Exception {
        List<String> names = JsonPath.read(apiDocs(), "$.tags[*].name");

        assertThat(names).contains("Claims", "Policies");
        List<String> claims = JsonPath.read(apiDocs(), "$.tags[?(@.name=='Claims')].description");
        List<String> policies = JsonPath.read(apiDocs(), "$.tags[?(@.name=='Policies')].description");
        assertThat(claims.get(0)).isNotBlank();
        assertThat(policies.get(0)).isNotBlank();
    }

    @Test
    void submittingAClaimDocumentsTheIdempotencyKeyHeaderAsAnOptionalParameter() throws Exception {
        List<String> in = JsonPath.read(apiDocs(),
                "$.paths['/api/v1/claims'].post.parameters[?(@.name=='Idempotency-Key')].in");
        List<Boolean> required = JsonPath.read(apiDocs(),
                "$.paths['/api/v1/claims'].post.parameters[?(@.name=='Idempotency-Key')].required");

        assertThat(in).containsExactly("header");
        assertThat(required).containsExactly(false);
    }

    @Test
    void submittingAClaimDocumentsEveryStatusCodeItCanReturn() throws Exception {
        Map<String, Object> responses = JsonPath.read(apiDocs(), "$.paths['/api/v1/claims'].post.responses");

        assertThat(responses.keySet()).contains("200", "201", "400", "401", "403", "409", "422");
    }

    @Test
    void theActionsDocumentTheirConflictsAndRuleViolations() throws Exception {
        Map<String, Set<String>> expected = Map.of(
                "/api/v1/claims/{claimNumber}/pick-up", Set.of("200", "403", "404", "409"),
                "/api/v1/claims/{claimNumber}/assign", Set.of("200", "400", "403", "404", "409", "422"),
                "/api/v1/claims/{claimNumber}/approve", Set.of("200", "400", "403", "404", "409", "422"),
                "/api/v1/claims/{claimNumber}/reject", Set.of("200", "400", "403", "404", "409"),
                "/api/v1/claims/{claimNumber}/escalate", Set.of("200", "400", "403", "404", "409"),
                "/api/v1/claims/{claimNumber}/assessments", Set.of("201", "400", "403", "404", "409"),
                "/api/v1/claims/{claimNumber}/information-requests", Set.of("201", "400", "403", "404", "409"));

        for (Map.Entry<String, Set<String>> entry : expected.entrySet()) {
            Map<String, Object> responses = JsonPath.read(apiDocs(),
                    "$.paths['" + entry.getKey() + "'].post.responses");
            assertThat(responses.keySet()).as(entry.getKey()).containsAll(entry.getValue());
        }
    }

    // ---- the examples must be valid requests ---------------------------------------------------------------------------------

    @Test
    void thePostClaimsOperationHasTheTwoNamedExamples() throws Exception {
        Map<String, Object> examples = JsonPath.read(apiDocs(),
                "$.paths['/api/v1/claims'].post.requestBody.content['application/json'].examples");

        assertThat(examples.keySet()).containsExactlyInAnyOrder("Motor claim", "Property claim");
    }

    @ParameterizedTest
    @CsvSource({"Motor claim, MOTOR", "Property claim, PROPERTY"})
    void theNamedClaimExamplesAreValidRequestsOfTheRightType(String name, String type) throws Exception {
        Object value = JsonPath.read(apiDocs(),
                "$.paths['/api/v1/claims'].post.requestBody.content['application/json'].examples['" + name + "'].value");

        SubmitClaimRequest request = objectMapper.convertValue(value, SubmitClaimRequest.class);

        assertThat(request.claimType().name()).isEqualTo(type);
        assertThat(validator.validate(request)).isEmpty();
        assertThat(type.equals("MOTOR") ? request.motor() : request.property()).isNotNull();
        assertThat(type.equals("MOTOR") ? request.property() : request.motor()).isNull();
    }

    @Test
    void theAssessmentApprovalAndRejectionExamplesAreValidRequests() throws Exception {
        Object assessment = JsonPath.read(apiDocs(), "$.paths['/api/v1/claims/{claimNumber}/assessments'].post"
                + ".requestBody.content['application/json'].examples['Coverage confirmed'].value");
        Object approval = JsonPath.read(apiDocs(), "$.paths['/api/v1/claims/{claimNumber}/approve'].post"
                + ".requestBody.content['application/json'].examples['Approve for less'].value");
        Object rejection = JsonPath.read(apiDocs(), "$.paths['/api/v1/claims/{claimNumber}/reject'].post"
                + ".requestBody.content['application/json'].examples['Not covered'].value");

        RecordAssessmentRequest assessmentRequest = objectMapper.convertValue(assessment, RecordAssessmentRequest.class);
        RejectRequest rejectRequest = objectMapper.convertValue(rejection, RejectRequest.class);

        assertThat(validator.validate(assessmentRequest)).isEmpty();
        assertThat(assessmentRequest.coverageConfirmed()).isTrue();
        assertThat(validator.validate(objectMapper.convertValue(approval, ApproveRequest.class))).isEmpty();
        assertThat(validator.validate(rejectRequest)).isEmpty();
        assertThat(rejectRequest.reasonCode().name()).isEqualTo("NOT_COVERED");
    }

    @Test
    void theRequestSchemasCarryExamplesForEveryField() throws Exception {
        for (String schema : List.of("AssignClaimRequest", "RecordAssessmentRequest", "CreateInformationRequest",
                "RespondToInformationRequest", "EscalateRequest", "ApproveRequest", "RejectRequest",
                "SubmitClaimRequest", "UpsertPolicyRequest")) {
            Map<String, Map<String, Object>> properties = JsonPath.read(apiDocs(),
                    "$.components.schemas." + schema + ".properties");
            properties.forEach((name, definition) -> {
                if (!definition.containsKey("$ref")) {
                    assertThat(definition).as(schema + "." + name).containsKey("example");
                }
            });
        }
    }
}
