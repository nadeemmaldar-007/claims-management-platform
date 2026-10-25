package com.apac.claims.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.apac.claims.market.api.dto.MarketResponse;
import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.shared.exception.ResourceNotFoundException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    @Mock
    private MarketRepository markets;
    @InjectMocks
    private MarketService service;

    private static Market japan() {
        return new Market("JP", "Japan", "JPY", new BigDecimal("1000000.00"), true);
    }

    @Test
    void listMapsEveryMarketInTheOrderTheRepositoryGivesThem() {
        when(markets.findAllByOrderByIdAsc()).thenReturn(List.of(
                new Market("IN", "India", "INR", new BigDecimal("500000.00"), true), japan()));

        List<MarketResponse> result = service.list();

        assertThat(result).extracting(MarketResponse::code).containsExactly("IN", "JP");
        assertThat(result.get(1)).isEqualTo(
                new MarketResponse("JP", "Japan", "JPY", new BigDecimal("1000000.00"), true));
    }

    @Test
    void theCodeIsNormalisedToUpperCaseBeforeTheLookup() {
        when(markets.findByCode("JP")).thenReturn(Optional.of(japan()));

        MarketResponse response = service.getByCode("  jp ");

        assertThat(response.currencyCode()).isEqualTo("JPY");
        verify(markets).findByCode("JP");
    }

    @Test
    void anUnknownCodeIsReportedAsNotFoundWithTheNormalisedCode() {
        when(markets.findByCode("ZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByCode("zz"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Market 'ZZ' not found");
    }

    @Test
    void aNullCodeIsNotFoundRatherThanAServerError() {
        when(markets.findByCode("")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByCode(null)).isInstanceOf(ResourceNotFoundException.class);
    }
}
