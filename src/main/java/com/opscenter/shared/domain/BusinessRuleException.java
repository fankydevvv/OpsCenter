package com.opscenter.shared.domain;

import org.springframework.http.HttpStatus;

/**
 * The request is well-formed and the resource exists, but a business rule forbids the operation
 * -> HTTP 422 (04-API §17). Example: an administrator trying to lock their own account.
 */
public class BusinessRuleException extends DomainException {

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
