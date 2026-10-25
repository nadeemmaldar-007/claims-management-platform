package com.apac.claims.market.api.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/** A market as shown by the API. */
@Schema(description = "One of the six markets.")
public record MarketResponse(
        @Schema(description = "Two-letter market code.", example = "IN") String code,
        @Schema(description = "Country name.", example = "India") String countryName,
        @Schema(description = "ISO 4217 currency code of the market; every claim of the market uses it.",
                example = "INR") String currencyCode,
        @Schema(description = "The largest amount an officer may approve without escalating, in the market's "
                + "currency.", example = "500000.00") BigDecimal escalationThreshold,
        @Schema(description = "Whether the market accepts new registrations and claims.", example = "true")
        boolean active) {
}
