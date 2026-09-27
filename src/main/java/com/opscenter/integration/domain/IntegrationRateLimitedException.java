package com.opscenter.integration.domain;

import com.opscenter.shared.domain.DomainException;

import org.springframework.http.HttpStatus;

/** 429 {@code INTEGRATION_RATE_LIMITED} (D-53). Alertmanager retries later on its own. */
public class IntegrationRateLimitedException extends DomainException {

    public IntegrationRateLimitedException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, IntegrationErrorCodes.INTEGRATION_RATE_LIMITED, message);
    }
}
