package com.apac.claims.reporting.api.dto;

import java.math.BigDecimal;

import com.apac.claims.claim.domain.ClaimType;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The exposure of one market and one claim type, in the local currency and in the base currency. Every figure is converted
 * on its own (amount times the configured rate, rounded once), so a base figure can differ by a cent from the sum of its parts.
 */
@Schema(description = "Exposure of one market and claim type.")
public record ExposureRow(
        @Schema(description = "Market code.", example = "IN") String marketCode,
        @Schema(description = "MOTOR or PROPERTY.", example = "MOTOR") ClaimType claimType,
        @Schema(description = "Currency of the local amounts.", example = "INR") String currencyCode,
        @Schema(description = "Sum of the claimed amounts of the open claims (SUBMITTED, UNDER_REVIEW, "
                + "PENDING_INFORMATION, ESCALATED), local currency.", example = "45000.00") BigDecimal openClaimedAmount,
        @Schema(description = "Sum of the approved amounts of the APPROVED claims, which wait for payment, local "
                + "currency.", example = "39000.00") BigDecimal approvedAwaitingPayment,
        @Schema(description = "Sum of the settled amounts of the SETTLED claims, local currency.", example = "0.00")
        BigDecimal settledTotal,
        @Schema(description = "openClaimedAmount plus approvedAwaitingPayment, local currency.", example = "84000.00")
        BigDecimal outstandingExposure,
        @Schema(description = "openClaimedAmount in the base currency.", example = "540.00")
        BigDecimal openClaimedAmountBase,
        @Schema(description = "approvedAwaitingPayment in the base currency.", example = "468.00")
        BigDecimal approvedAwaitingPaymentBase,
        @Schema(description = "settledTotal in the base currency.", example = "0.00") BigDecimal settledTotalBase,
        @Schema(description = "outstandingExposure in the base currency.", example = "1008.00")
        BigDecimal outstandingExposureBase) {
}
