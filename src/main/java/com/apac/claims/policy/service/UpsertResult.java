package com.apac.claims.policy.service;

import com.apac.claims.policy.api.dto.PolicyResponse;

/**
 * The outcome of creating or updating a policy; the controller needs to know which one happened, because a create
 * answers 201 with a {@code Location} header and an update answers 200.
 */
public record UpsertResult(PolicyResponse policy, boolean created) {
}
