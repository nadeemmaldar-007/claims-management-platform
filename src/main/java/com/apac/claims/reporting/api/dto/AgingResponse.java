package com.apac.claims.reporting.api.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** The aging report: the open claims of the scope by age since submission. */
@Schema(description = "Open claims by age since submission.")
public record AgingResponse(
        @Schema(description = "Currency of the base figures.", example = "USD") String baseCurrency,
        @Schema(description = "Where the exchange rates come from. The rates are fixed and illustrative.",
                example = "STATIC_ILLUSTRATIVE") String rateSource,
        @Schema(description = "The four bands, always all four, oldest last.") List<AgingBucket> buckets) {
}
