package com.apac.claims.reporting.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;

import org.junit.jupiter.api.Test;

/** The start-up check: a market whose currency has no exchange rate stops the application. */
class RateConfigurationCheckTest {

    private final MarketRepository markets = mock(MarketRepository.class);
    private final RateConfigurationCheck check = new RateConfigurationCheck(markets, ReportData.converter());

    private static Market market(String code, String currency) {
        return new Market(code, code, currency, new BigDecimal("1000.00"), true);
    }

    @Test
    void theApplicationStartsWhenEveryMarketCurrencyHasARate() {
        when(markets.findAll()).thenReturn(List.of(market("IN", "INR"), market("SG", "SGD"), market("AU", "AUD"),
                market("JP", "JPY"), market("MY", "MYR"), market("TH", "THB")));

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    }

    @Test
    void aMarketInTheBaseCurrencyNeedsNoRate() {
        when(markets.findAll()).thenReturn(List.of(market("US", "USD")));

        assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    }

    @Test
    void theApplicationDoesNotStartWhenACurrencyHasNoRateAndTheMessageNamesIt() {
        when(markets.findAll()).thenReturn(List.of(market("IN", "INR"), market("KR", "KRW")));

        assertThatThrownBy(() -> check.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KRW");
    }
}
