package com.opscenter.shared.domain;

import org.springframework.http.HttpStatus;

/**
 * The request clashes with current state -> HTTP 409 (04-API §17): duplicate unique value,
 * stale {@code version}, invalid state transition.
 */
public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
