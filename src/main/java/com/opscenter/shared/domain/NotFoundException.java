package com.opscenter.shared.domain;

import org.springframework.http.HttpStatus;

/**
 * A resource addressed by the request does not exist -> HTTP 404 (04-API §17).
 * Example: {@code new NotFoundException("USER_NOT_FOUND", "User " + id + " not found")}.
 */
public class NotFoundException extends DomainException {

    public NotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
