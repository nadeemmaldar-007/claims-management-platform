package com.apac.claims.reporting.api.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** The exposure report: money at stake per market and claim type, and the total in the base currency. */
@Schema(description = "Liability exposure per market and claim type.")
public record ExposureResponse(
        @Schema(description = "Currency of the base figures and of the total.", example = "USD") String baseCurrency,
        @Schema(description = "Where the exchange rates come from. The rates are fixed and illustrative, not real "
                + "exchange rates.", example = "STATIC_ILLUSTRATIVE") String rateSource,
        @Schema(description = "One row per market in scope and claim type, also when there is nothing to show.")
        List<ExposureRow> rows,
        @Schema(description = "The total of all rows, base currency only.") ExposureTotal total) {
}
