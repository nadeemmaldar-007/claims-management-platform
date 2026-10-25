package com.apac.claims.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

import com.apac.claims.shared.exception.ValidationFailedException;

/**
 * Currency-aware money rules: the number of decimal places comes from the currency (JPY 0, most others 2),
 * so no module hard-codes a scale. Null and sign checks stay with the callers, which know the field.
 */
public final class MoneyRules {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private MoneyRules() {
    }

    /** Number of decimal places the currency allows. */
    public static int scaleFor(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new IllegalArgumentException("Currency code must not be blank");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown currency code: " + currencyCode, e);
        }
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            // Pseudo-currencies such as XXX define no fraction digits and cannot hold an amount.
            throw new IllegalArgumentException("Currency has no decimal places defined: " + currencyCode);
        }
        return digits;
    }

    /** Rounds half up to the currency's scale, for example 10.005 SGD becomes 10.01. */
    public static BigDecimal normalize(BigDecimal amount, String currencyCode) {
        Objects.requireNonNull(amount, "amount");
        return amount.setScale(scaleFor(currencyCode), RoundingMode.HALF_UP);
    }

    /**
     * Rejects an amount that has more decimal places than the currency allows, for example 10.5 JPY.
     * Trailing zeros do not count, so 10.50 SGD and 10.00 JPY are accepted.
     */
    public static void requireValidScale(BigDecimal amount, String currencyCode, String fieldName) {
        Objects.requireNonNull(amount, "amount");
        int allowed = scaleFor(currencyCode);
        if (amount.stripTrailingZeros().scale() > allowed) {
            String places = allowed == 0 ? "no decimal places" : "at most " + allowed + " decimal places";
            throw ValidationFailedException.forField(fieldName, "must have " + places + " for " + currencyCode);
        }
    }

    /** Computes {@code percent} percent of {@code base}, rounded half up to the currency's scale. */
    public static BigDecimal percentOf(BigDecimal base, BigDecimal percent, String currencyCode) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(percent, "percent");
        return base.multiply(percent).divide(ONE_HUNDRED, scaleFor(currencyCode), RoundingMode.HALF_UP);
    }
}
