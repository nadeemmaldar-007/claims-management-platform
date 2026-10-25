package com.apac.claims.claim.service;

import com.apac.claims.shared.exception.ErrorCode;
import com.apac.claims.shared.exception.UpstreamServiceException;

/** The payment gateway could not be reached or refused the payment (502 {@code PAYMENT_GATEWAY_UNAVAILABLE}). */
public class PaymentGatewayException extends UpstreamServiceException {

    public PaymentGatewayException(String message) {
        super(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, message, cause);
    }
}
