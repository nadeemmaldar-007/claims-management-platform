package com.apac.claims.claim.service;

import com.apac.claims.claim.api.dto.ClaimDetailResponse;

/**
 * The outcome of a submission. {@code replay} is true when the idempotency key had been used before and the original
 * claim was returned instead of a new one; the controller answers 200 with a header in that case and 201 otherwise.
 */
public record SubmissionResult(ClaimDetailResponse claim, boolean replay) {
}
