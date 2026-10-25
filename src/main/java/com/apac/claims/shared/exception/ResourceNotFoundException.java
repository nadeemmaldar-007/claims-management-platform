package com.apac.claims.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * The resource does not exist or is outside the caller's scope. Both cases look the same (404)
 * so that the existence of other users' data is never revealed.
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, message);
    }

    /** Builds the message from a resource type and its identifier, for example {@code Claim 'CLM-10001' not found}. */
    public static ResourceNotFoundException of(String resourceType, Object identifier) {
        return new ResourceNotFoundException(resourceType + " '" + identifier + "' not found");
    }
}
