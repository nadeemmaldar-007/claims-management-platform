package com.apac.claims.reporting.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/** The overview: where the claims of the scope stand, how much money is at stake, and what is late. */
@Schema(description = "The state of the claims of the scope at a glance.")
public record OverviewResponse(
        @Schema(description = "Number of claims per status. All seven statuses are always present, with 0 where there "
                + "is none.", example = "{\"SUBMITTED\": 2, \"UNDER_REVIEW\": 1, \"PENDING_INFORMATION\": 0, "
                + "\"ESCALATED\": 1, \"APPROVED\": 1, \"REJECTED\": 0, \"SETTLED\": 0}")
        Map<String, Long> countsByStatus,
        @Schema(description = "Number of claims per claim type; both types are always present.",
                example = "{\"MOTOR\": 3, \"PROPERTY\": 2}") Map<String, Long> countsByType,
        @Schema(description = "Outstanding exposure (open claimed amounts plus approved amounts waiting for payment) per "
                + "market, in the local currency and in the base currency.")
        List<MarketOutstanding> outstandingExposureByMarket,
        @Schema(description = "Outstanding exposure of all markets in scope, base currency.", example = "2968.00")
        BigDecimal outstandingExposureBase,
        @Schema(description = "Currency of the base figures.", example = "USD") String baseCurrency,
        @Schema(description = "Where the exchange rates come from. The rates are fixed and illustrative.",
                example = "STATIC_ILLUSTRATIVE") String rateSource,
        @Schema(description = "Claims in the queue: SUBMITTED and not yet taken by an officer.", example = "2")
        long queueSize,
        @Schema(description = "Claims in the queue that have waited longer than the first-review target (48 hours).",
                example = "1") long queueSlaBreaches,
        @Schema(description = "Open claims that an officer has but that are older than the decision target (240 hours "
                + "since submission).", example = "0") long openClaimSlaBreaches,
        @Schema(description = "The open claims by age since submission, the four bands.") List<AgingBucket> aging) {
}
