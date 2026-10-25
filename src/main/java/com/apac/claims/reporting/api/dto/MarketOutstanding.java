package com.apac.claims.reporting.api.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/** The outstanding exposure of one market, in its own currency and in the base currency. */
@Schema(description = "Outstanding exposure of one market.")
public record MarketOutstanding(
        @Schema(description = "Market code.", example = "IN") String marketCode,
        @Schema(description = "Currency of the local amount.", example = "INR") String currencyCode,
        @Schema(description = "Open claimed amounts plus approved amounts waiting for payment, local currency.",
                example = "84000.00") BigDecimal outstandingExposure,
        @Schema(description = "The same in the base currency.", example = "1008.00")
        BigDecimal outstandingExposureBase) {
}
