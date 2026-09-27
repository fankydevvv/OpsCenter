package com.opscenter.shared.domain;

import org.springframework.http.HttpStatus;

/**
 * The request itself is unacceptable (HTTP 400) for a reason the application layer detects after
 * bean validation, for example a {@code sort} parameter naming a property that must not be
 * exposed (04-API §2.4, §17). Bean-validation failures still go through the exception handler's
 * own {@code VALIDATION_FAILED} mapping with {@code fieldErrors}; this class covers the rest.
 */
public class InvalidRequestException extends DomainException {

    public InvalidRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
