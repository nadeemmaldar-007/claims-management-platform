package com.apac.claims.claim.api.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/** Approval of a claim. Both fields are optional, and the whole body may be left out. */
@Schema(description = "Optional details of an approval.")
public record ApproveRequest(
        @Schema(description = "Amount to approve. Defaults to the recommended payout of the latest assessment; "
                + "must be above 0 and not above that payout.", example = "39000.00", nullable = true)
        @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0") BigDecimal approvedAmount,
        @Schema(description = "Remarks shown to the claimant with the decision. At most 1000 characters.",
                example = "Approved as assessed.", nullable = true)
        @Size(max = 1000) String remarks) {
}
