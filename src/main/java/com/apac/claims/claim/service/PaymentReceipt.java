package com.apac.claims.claim.service;

/** The proof of a payment: its reference, for example {@code PAY-0123456789AB}. */
public record PaymentReceipt(String reference) {
}
