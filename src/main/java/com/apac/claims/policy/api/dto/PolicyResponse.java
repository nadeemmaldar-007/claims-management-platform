package com.apac.claims.policy.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.apac.claims.policy.domain.PolicyStatus;
import com.apac.claims.policy.domain.ProductType;

import io.swagger.v3.oas.annotations.media.Schema;

/** A policy as shown by the API. */
@Schema(description = "A policy.")
public record PolicyResponse(
        @Schema(description = "Policy number, always upper case.", example = "MOT-IN-0001") String policyNumber,
        @Schema(description = "MOTOR or PROPERTY.", example = "MOTOR") ProductType productType,
        @Schema(description = "Market code.", example = "IN") String marketCode,
        @Schema(description = "User id of the holder.", example = "claimant.in") String holderUserId,
        @Schema(description = "Vehicle registration number or property address.", example = "MH12AB1234")
        String insuredItem,
        @Schema(description = "Sum insured in the currency of the market.", example = "1500000.00")
        BigDecimal sumInsured,
        @Schema(description = "Deductible in the currency of the market.", example = "5000.00") BigDecimal deductible,
        @Schema(description = "Currency of the amounts, from the market.", example = "INR") String currencyCode,
        @Schema(description = "First day of cover.", example = "2026-01-01") LocalDate validFrom,
        @Schema(description = "Last day of cover.", example = "2027-12-31") LocalDate validTo,
        @Schema(description = "ACTIVE, LAPSED or CANCELLED.", example = "ACTIVE") PolicyStatus status,
        @Schema(description = "When the policy was created (UTC).", example = "2026-10-25T10:15:30Z")
        Instant createdAt,
        @Schema(description = "When the policy was last changed (UTC).", example = "2026-10-25T10:15:30Z")
        Instant updatedAt) {
}
