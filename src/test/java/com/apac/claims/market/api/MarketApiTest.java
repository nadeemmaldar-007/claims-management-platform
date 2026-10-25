package com.apac.claims.market.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractApiTest;
import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class MarketApiTest extends AbstractApiTest {

    private String tokenFor(Role role) throws Exception {
        return switch (role) {
            case CLAIMANT -> claimantToken();
            case OFFICER -> officerToken();
            case MANAGER -> managerToken();
        };
    }

    @Test
    void anAuthenticatedUserGetsTheSixMarketsInTheSeededOrder() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/markets").header("Authorization", bearer(claimantToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andReturn();

        List<String> codes = JsonPath.read(result.getResponse().getContentAsString(), "$[*].code");
        assertThat(codes).containsExactly("IN", "SG", "AU", "JP", "MY", "TH");
    }

    @ParameterizedTest
    @CsvSource({
            "IN, India, INR, 500000.00",
            "SG, Singapore, SGD, 10000.00",
            "AU, Australia, AUD, 10000.00",
            "JP, Japan, JPY, 1000000.00",
            "MY, Malaysia, MYR, 25000.00",
            "TH, Thailand, THB, 200000.00"
    })
    void eachMarketHasItsCurrencyAndEscalationThreshold(String code, String country, String currency,
                                                        String threshold) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/markets/" + code).header("Authorization", bearer(officerToken())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.countryName").value(country))
                .andExpect(jsonPath("$.currencyCode").value(currency))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();

        Number thresholdInJson = JsonPath.read(result.getResponse().getContentAsString(), "$.escalationThreshold");
        assertThat(new BigDecimal(thresholdInJson.toString())).isEqualByComparingTo(new BigDecimal(threshold));
    }

    @Test
    void theListCarriesTheSameFiguresAsTheSingleLookup() throws Exception {
        mvc.perform(get("/api/v1/markets").header("Authorization", bearer(managerToken())))
                .andExpect(jsonPath("$[?(@.code == 'JP')].currencyCode").value("JPY"))
                .andExpect(jsonPath("$[?(@.code == 'IN')].currencyCode").value("INR"));
    }

    @Test
    void theCodeLookupIsCaseInsensitive() throws Exception {
        mvc.perform(get("/api/v1/markets/jp").header("Authorization", bearer(claimantToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("JP"));
        mvc.perform(get("/api/v1/markets/Sg").header("Authorization", bearer(claimantToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SG"));
    }

    @Test
    void anUnknownCodeIsA404WithTheResourceNotFoundCode() throws Exception {
        mvc.perform(get("/api/v1/markets/ZZ").header("Authorization", bearer(claimantToken())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void withoutATokenTheMarketsAreNotAvailable() throws Exception {
        mvc.perform(get("/api/v1/markets")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/markets/IN")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyRoleMayReadTheMarkets(Role role) throws Exception {
        mvc.perform(get("/api/v1/markets").header("Authorization", bearer(tokenFor(role))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6));
    }
}
