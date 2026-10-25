package com.apac.claims.market.service;

import java.util.List;
import java.util.Locale;

import com.apac.claims.market.api.dto.MarketResponse;
import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.shared.exception.ResourceNotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read access to the markets. Codes are case-insensitive: they are normalised to upper case before the lookup. */
@Service
public class MarketService {

    private final MarketRepository markets;

    public MarketService(MarketRepository markets) {
        this.markets = markets;
    }

    /** All markets in the order they were seeded. */
    @Transactional(readOnly = true)
    public List<MarketResponse> list() {
        return markets.findAllByOrderByIdAsc().stream().map(MarketService::toResponse).toList();
    }

    /** @throws ResourceNotFoundException when no market has the code */
    @Transactional(readOnly = true)
    public MarketResponse getByCode(String code) {
        String normalised = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return markets.findByCode(normalised)
                .map(MarketService::toResponse)
                .orElseThrow(() -> ResourceNotFoundException.of("Market", normalised));
    }

    private static MarketResponse toResponse(Market market) {
        return new MarketResponse(market.getCode(), market.getCountryName(), market.getCurrencyCode(),
                market.getEscalationThreshold(), market.isActive());
    }
}
