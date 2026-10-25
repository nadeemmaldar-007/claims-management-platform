package com.apac.claims.claim.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.DamageType;
import com.apac.claims.claim.domain.PerilType;
import com.apac.claims.claim.domain.PropertyType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * First notice of loss. Exactly one of {@code motor} and {@code property} must be given, matching the claim type;
 * that rule, the date range and the currency-specific decimal places depend on other data and are checked by the
 * service. The currency is not part of the request: it is the currency of the claimant's market.
 */
@Schema(description = "First notice of loss: what happened, which policy it falls under, and how much is claimed.")
public record SubmitClaimRequest(
        @Schema(description = "MOTOR or PROPERTY. It must match the type of the policy.", example = "MOTOR",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull ClaimType claimType,
        @Schema(description = "Number of one of your own policies: 6 to 20 letters, digits or dashes.",
                example = "MOT-IN-0001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{6,20}$",
                message = "must be 6 to 20 letters, digits or dashes") String policyNumber,
        @Schema(description = "Amount claimed in the currency of your market. Above 0, at most 100000000, with the "
                + "decimal places of the currency (JPY has none). It must not exceed the sum insured.",
                example = "45000.00", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
        @DecimalMax(value = "100000000", message = "must not exceed 100000000") BigDecimal claimedAmount,
        @Schema(description = "Day of the incident. Not older than 5 years and not later than tomorrow in UTC.",
                example = "2026-10-15", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull LocalDate incidentDate,
        @Schema(description = "Where it happened.", example = "MG Road, Bengaluru",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 200) String incidentLocation,
        @Schema(description = "What happened, 10 to 1000 characters.",
                example = "Rear-ended at a traffic signal; bumper and boot lid damaged.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 10, max = 1000) String description,
        @Schema(description = "Vehicle details. Required for a MOTOR claim, not allowed for a PROPERTY claim.")
        @Valid MotorDetails motor,
        @Schema(description = "Property details. Required for a PROPERTY claim, not allowed for a MOTOR claim.")
        @Valid PropertyDetails property) {

    /** The vehicle block of a motor claim. */
    @Schema(description = "Details of the damaged vehicle.")
    public record MotorDetails(
            @Schema(description = "Registration number of the vehicle.", example = "MH12AB1234",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 20) String vehicleRegistrationNumber,
            @Schema(description = "Make of the vehicle.", example = "Hyundai",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 50) String vehicleMake,
            @Schema(description = "Model of the vehicle.", example = "i20",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 50) String vehicleModel,
            @Schema(description = "Year of manufacture, from 1980 to next year.", example = "2021",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Integer vehicleYear,
            @Schema(description = "COLLISION, THEFT, FIRE, FLOOD, VANDALISM or OTHER.", example = "COLLISION",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull DamageType damageType,
            @Schema(description = "Police report number, if there is one.", example = "FIR-2026-5521",
                    nullable = true)
            @Size(max = 50) String policeReportNumber) {
    }

    /** The property block of a property claim. */
    @Schema(description = "Details of the damaged property.")
    public record PropertyDetails(
            @Schema(description = "Address of the property.",
                    example = "Flat 402, Lakeview Apartments, Powai, Mumbai 400076",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 300) String propertyAddress,
            @Schema(description = "RESIDENTIAL, COMMERCIAL or INDUSTRIAL.", example = "RESIDENTIAL",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull PropertyType propertyType,
            @Schema(description = "FIRE, FLOOD, STORM, THEFT, VANDALISM or OTHER.", example = "FLOOD",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull PerilType perilType) {
    }
}
