package com.apac.claims.claim.api.dto;

import com.apac.claims.claim.domain.PaymentMethod;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Settlement of an approved claim: how it is paid. The amount is the approved amount and cannot be changed here. */
@Schema(description = "How an approved claim is paid.")
public record SettleClaimRequest(
        @Schema(description = "BANK_TRANSFER or CHEQUE.", example = "BANK_TRANSFER",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull PaymentMethod paymentMethod,
        @Schema(description = "Remarks shown to the claimant with the payment. At most 1000 characters.",
                example = "Paid to the account on file.", nullable = true)
        @Size(max = 1000) String remarks) {
}
