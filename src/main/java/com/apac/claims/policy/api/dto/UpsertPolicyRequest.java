package com.apac.claims.policy.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.apac.claims.policy.domain.PolicyStatus;
import com.apac.claims.policy.domain.ProductType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Creates or updates a policy. It stands in for the feed from the policy administration system, so the same request
 * is used for both; the policy number comes from the path. Rules that need other data (the holder, the currency of the
 * market, what an existing policy looks like) are checked by the service.
 */
@Schema(description = "A policy to create or update.")
public record UpsertPolicyRequest(
        @Schema(description = "MOTOR or PROPERTY. Cannot be changed on an existing policy.", example = "MOTOR",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull ProductType productType,
        @Schema(description = "Market code of the policy; the holder must belong to this market. Cannot be changed on "
                + "an existing policy.", example = "IN", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter market code") String marketCode,
        @Schema(description = "User id of the holder: an active claimant of the market. Cannot be changed on an "
                + "existing policy.", example = "claimant.in", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 30) String holderUserId,
        @Schema(description = "The insured vehicle registration number or property address.",
                example = "MH14CD5678", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 200) String insuredItem,
        @Schema(description = "Sum insured in the currency of the market. Must be above zero and use the decimal "
                + "places of the currency.", example = "1500000.00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
        @DecimalMax(value = "1000000000000", message = "must not exceed 1000000000000") BigDecimal sumInsured,
        @Schema(description = "Deductible in the currency of the market. Zero or more, and not above the sum insured.",
                example = "5000.00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin(value = "0", message = "must not be negative") BigDecimal deductible,
        @Schema(description = "First day of cover.", example = "2026-01-01",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull LocalDate validFrom,
        @Schema(description = "Last day of cover. Must be after validFrom.", example = "2027-12-31",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull LocalDate validTo,
        @Schema(description = "ACTIVE, LAPSED or CANCELLED. Only an ACTIVE policy accepts a claim.",
                example = "ACTIVE", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull PolicyStatus status) {
}
