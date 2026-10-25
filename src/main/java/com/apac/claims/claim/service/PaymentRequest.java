package com.apac.claims.claim.service;

import java.math.BigDecimal;

import com.apac.claims.claim.domain.PaymentMethod;

/** What the payment gateway needs to pay one claim: who (the claim number), how much, in which currency, and how. */
public record PaymentRequest(String claimNumber, BigDecimal amount, String currencyCode, PaymentMethod paymentMethod) {
}
