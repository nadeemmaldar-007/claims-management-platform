package com.apac.claims.reporting.api.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/** The total over every row of the exposure report, in the base currency only: local currencies are never added. */
@Schema(description = "Total exposure in the base currency.")
public record ExposureTotal(
        @Schema(description = "Open claimed amounts, base currency.", example = "2500.00")
        BigDecimal openClaimedAmount,
        @Schema(description = "Approved amounts waiting for payment, base currency.", example = "468.00")
        BigDecimal approvedAwaitingPayment,
        @Schema(description = "Settled amounts, base currency.", example = "0.00") BigDecimal settledTotal,
        @Schema(description = "Sum of the outstandingExposure of the rows, base currency.", example = "2968.00")
        BigDecimal outstandingExposure) {
}
