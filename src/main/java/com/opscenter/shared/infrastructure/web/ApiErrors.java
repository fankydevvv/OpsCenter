package com.opscenter.shared.infrastructure.web;

import java.time.Instant;
import java.util.List;

import com.opscenter.shared.api.ApiError;
import com.opscenter.shared.api.FieldError;

import org.springframework.http.HttpStatus;

/**
 * Single factory for {@link ApiError} bodies so the exception handler, the authentication entry
 * point and the access-denied handler cannot drift apart: timestamp is "now" in UTC and the
 * request id always comes from the MDC populated by {@link RequestIdFilter}.
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static ApiError of(HttpStatus status, String code, String message) {
        return of(status, code, message, List.of());
    }

    public static ApiError of(HttpStatus status, String code, String message, List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), RequestIdFilter.currentRequestId(), status.value(), code, message,
                fieldErrors);
    }
}
