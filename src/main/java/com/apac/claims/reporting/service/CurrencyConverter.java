package com.apac.claims.reporting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;

import com.apac.claims.config.ReportingProperties;
import com.apac.claims.shared.money.MoneyRules;

import org.springframework.stereotype.Component;

/**
 * Converts amounts into the base currency with the configured static rates. The rates are illustrative and are only
 * there to show totals across markets in one currency; the response of every report says so ({@code
 * STATIC_ILLUSTRATIVE}). One conversion is one multiplication, rounded once, half up, to the decimal places of the base
 * currency: amounts of several rows are converted row by row and then added, never added in their local currencies.
 */
@Component
public class CurrencyConverter {

    private final String baseCurrency;
    private final String rateSource;
    private final Map<String, BigDecimal> rates;
    private final int baseScale;

    public CurrencyConverter(ReportingProperties properties) {
        this.baseCurrency = properties.baseCurrency();
        this.rateSource = properties.rateSource();
        this.rates = Map.copyOf(properties.rates());
        this.baseScale = MoneyRules.scaleFor(baseCurrency);
    }

    public String baseCurrency() {
        return baseCurrency;
    }

    public String rateSource() {
        return rateSource;
    }

    /** The amount in the base currency, rounded half up to its decimal places. */
    public BigDecimal toBase(BigDecimal amount, String currencyCode) {
        if (currencyCode.equals(baseCurrency)) {
            return amount.setScale(baseScale, RoundingMode.HALF_UP);
        }
        BigDecimal rate = rates.get(currencyCode);
        if (rate == null) {
            throw new IllegalStateException("No exchange rate is configured for " + currencyCode);
        }
        return amount.multiply(rate).setScale(baseScale, RoundingMode.HALF_UP);
    }

    /** Zero with the decimal places of the base currency. */
    public BigDecimal zeroBase() {
        return BigDecimal.ZERO.setScale(baseScale, RoundingMode.UNNECESSARY);
    }

    /** The amount with the decimal places of its own currency (yen none, the others two). */
    public BigDecimal local(BigDecimal amount, String currencyCode) {
        return MoneyRules.normalize(amount, currencyCode);
    }

    /**
     * Fails when one of the currencies has no rate, so that a market added without a rate stops the application at
     * start-up instead of failing in the middle of a report.
     */
    public void requireRatesFor(Collection<String> currencyCodes) {
        for (String currency : currencyCodes) {
            if (!currency.equals(baseCurrency) && !rates.containsKey(currency)) {
                throw new IllegalStateException("No exchange rate to " + baseCurrency + " is configured for the "
                        + "currency " + currency + " of a market; add it under app.reporting.rates");
            }
        }
    }
}
