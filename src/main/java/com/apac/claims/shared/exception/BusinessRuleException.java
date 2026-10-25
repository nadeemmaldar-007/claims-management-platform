package com.apac.claims.shared.exception;

import org.springframework.http.HttpStatus;

/** The request is well formed but breaks a business rule, such as a policy check or an approval limit (422). */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(ErrorCode errorCode, String message) {
        super(errorCode, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
