package com.apac.claims.market.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.apac.claims.support.RepositoryTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@RepositoryTest
class MarketRepositoryTest {

    @Autowired
    private MarketRepository markets;

    @Test
    void findByCodeReturnsTheSeededMarket() {
        assertThat(markets.findByCode("SG")).get().satisfies(market -> {
            assertThat(market.getCountryName()).isEqualTo("Singapore");
            assertThat(market.getCurrencyCode()).isEqualTo("SGD");
        });
    }

    @Test
    void findByCodeIsEmptyForAnUnknownCode() {
        assertThat(markets.findByCode("ZZ")).isEmpty();
    }

    @Test
    void existsByCodeAnswersForKnownAndUnknownCodes() {
        assertThat(markets.existsByCode("JP")).isTrue();
        assertThat(markets.existsByCode("ZZ")).isFalse();
    }

    @Test
    void codeLookupIsCaseSensitiveSoCallersMustNormalise() {
        assertThat(markets.findByCode("in")).isEmpty();
    }
}
