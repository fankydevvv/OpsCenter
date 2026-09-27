package com.opscenter.integration.domain;

import com.opscenter.shared.domain.DomainException;

import org.springframework.http.HttpStatus;

/**
 * 413 {@code PAYLOAD_TOO_LARGE}: the body exceeds the configured maximum (1 MiB by default). The
 * webhook reads at most "maximum + 1" bytes, so an oversized request never fills the memory.
 */
public class PayloadTooLargeException extends DomainException {

    public PayloadTooLargeException(long maxBytes) {
        super(HttpStatus.CONTENT_TOO_LARGE, IntegrationErrorCodes.PAYLOAD_TOO_LARGE,
                "Request body exceeds the maximum of " + maxBytes + " bytes");
    }
}
