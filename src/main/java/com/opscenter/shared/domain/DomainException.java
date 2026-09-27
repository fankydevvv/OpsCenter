package com.opscenter.shared.domain;

import org.springframework.http.HttpStatus;

/**
 * Base class of every business failure that must reach the client as an {@code ApiError}.
 * <p>
 * Carrying the HTTP status and the error code on the exception lets the domain/application layer
 * express <em>what</em> went wrong ({@code USER_NOT_FOUND}) while the single exception handler in
 * the web layer decides <em>how</em> it is rendered (04-API §2.3, §17). Controllers therefore never
 * contain try/catch blocks.
 */
public abstract class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected DomainException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
