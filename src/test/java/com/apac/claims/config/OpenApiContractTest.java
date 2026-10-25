package com.apac.claims.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.apac.claims.support.AbstractApiTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The committed API contract ({@code src/main/resources/openapi/claims-api.yaml}) and the live OpenAPI document
 * ({@code /v3/api-docs}) must describe the same operations: every {@code "METHOD /path"} of one is in the other. The
 * contract is exported from a running application; this test fails when code adds, removes or renames an endpoint
 * and the file is not exported again.
 */
class OpenApiContractTest extends AbstractApiTest {

    private static final String CONTRACT = "openapi/claims-api.yaml";
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    @Test
    void theCommittedContractAndTheLiveDocumentExposeTheSamePathsAndMethods() throws Exception {
        Set<String> committed = operations(readContract());
        Set<String> live = operations(readLiveDocument());

        assertThat(committed).as("the committed contract lists operations").isNotEmpty();
        assertThat(committed)
                .as(describeDifference(committed, live))
                .isEqualTo(live);
    }

    @Test
    void theDifferenceIsReportedForBothSides() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Set<String> contract = operations(json.readTree("""
                {"paths": {"/a": {"get": {}, "post": {}}, "/only-in-contract": {"delete": {}}}}"""));
        Set<String> live = operations(json.readTree("""
                {"paths": {"/a": {"get": {}}, "/b": {"put": {}, "parameters": []}}}"""));

        assertThat(contract).containsExactlyInAnyOrder("GET /a", "POST /a", "DELETE /only-in-contract");
        assertThat(live).containsExactlyInAnyOrder("GET /a", "PUT /b");
        assertThat(contract).isNotEqualTo(live);
        String message = describeDifference(contract, live);
        assertThat(message)
                .contains("missing from the live document: [DELETE /only-in-contract, POST /a]")
                .contains("missing from the committed contract: [PUT /b]");
        assertThat(describeDifference(live, live)).contains("missing from the live document: []")
                .contains("missing from the committed contract: []");
    }

    private JsonNode readContract() throws Exception {
        try (InputStream in = new ClassPathResource(CONTRACT).getInputStream()) {
            return new YAMLMapper().readTree(in);
        }
    }

    private JsonNode readLiveDocument() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    /** The set of {@code "METHOD /path"} strings of an OpenAPI document; path-level keys such as parameters are skipped. */
    private static Set<String> operations(JsonNode document) {
        Set<String> result = new TreeSet<>();
        JsonNode paths = document.path("paths");
        Iterator<Map.Entry<String, JsonNode>> entries = paths.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> path = entries.next();
            Iterator<String> keys = path.getValue().fieldNames();
            while (keys.hasNext()) {
                String key = keys.next();
                if (HTTP_METHODS.contains(key)) {
                    result.add(key.toUpperCase() + " " + path.getKey());
                }
            }
        }
        return result;
    }

    private static String describeDifference(Set<String> committed, Set<String> live) {
        Set<String> notLive = new TreeSet<>(committed);
        notLive.removeAll(live);
        Set<String> notCommitted = new TreeSet<>(live);
        notCommitted.removeAll(committed);
        return "operations missing from the live document: " + notLive
                + "; operations missing from the committed contract: " + notCommitted
                + " (export /v3/api-docs.yaml again to src/main/resources/openapi/claims-api.yaml)";
    }
}
