package com.opscenter.identity.domain;

import com.opscenter.shared.domain.DomainException;

import org.springframework.http.HttpStatus;

/**
 * The same login was refused too often in a short window -> HTTP 429 {@code AUTH_TOO_MANY_ATTEMPTS}
 * (04-API §18 "rate limit cho auth", D-09: 5 failures in 15 minutes).
 * Slowing down brute force is the goal; the account itself is <em>not</em> locked.
 */
public class TooManyLoginAttemptsException extends DomainException {

    public TooManyLoginAttemptsException(int maxFailures, long windowMinutes) {
        super(HttpStatus.TOO_MANY_REQUESTS, IdentityErrorCodes.AUTH_TOO_MANY_ATTEMPTS,
                "Too many failed login attempts (" + maxFailures + " in " + windowMinutes
                        + " minutes); try again later");
    }
}
