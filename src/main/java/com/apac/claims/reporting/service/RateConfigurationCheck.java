package com.apac.claims.reporting.service;

import java.util.Set;
import java.util.stream.Collectors;

import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Stops the application at start-up when the currency of a market has no exchange rate in {@code app.reporting.rates}.
 * Without it the first report that meets that market would fail with a half-computed answer; with it the mistake is
 * found when the configuration is wrong, which is when someone can fix it.
 */
@Component
public class RateConfigurationCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RateConfigurationCheck.class);

    private final MarketRepository markets;
    private final CurrencyConverter converter;

    public RateConfigurationCheck(MarketRepository markets, CurrencyConverter converter) {
        this.markets = markets;
        this.converter = converter;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> currencies = markets.findAll().stream().map(Market::getCurrencyCode).collect(Collectors.toSet());
        converter.requireRatesFor(currencies);
        log.info("Exchange rates found for {} market currencies, base currency {}", currencies.size(),
                converter.baseCurrency());
    }
}
