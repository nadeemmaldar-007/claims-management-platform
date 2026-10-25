package com.apac.claims.reporting.api.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One age band of the open claims. The age is the number of whole days since the claim was submitted, so a claim that is
 * 2 days and 23 hours old is still in the first band.
 */
@Schema(description = "Open claims of one age band.")
public record AgingBucket(
        @Schema(description = "The band: 0-2 days, 3-7 days, 8-14 days or 15+ days since submission.",
                example = "3-7 days") String label,
        @Schema(description = "Number of open claims (SUBMITTED, UNDER_REVIEW, PENDING_INFORMATION, ESCALATED) in the "
                + "band.", example = "4") long count,
        @Schema(description = "Claimed amount of those claims in the base currency; each market is converted on its own "
                + "and the results are added.", example = "1250.50") BigDecimal outstandingExposureBase,
        @Schema(description = "Claimed amount in the currency of the market. Only given when the report covers one "
                + "market.", example = "104208.33", nullable = true) BigDecimal outstandingExposureLocal,
        @Schema(description = "Currency of outstandingExposureLocal; only given with it.", example = "INR",
                nullable = true) String currencyCode) {
}
