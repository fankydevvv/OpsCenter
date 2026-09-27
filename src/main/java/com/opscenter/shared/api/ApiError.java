package com.opscenter.shared.api;

import java.time.Instant;
import java.util.List;

/**
 * The one and only error body of the API (04-API §2.3).
 * <p>
 * A custom record is used instead of Spring's {@code ProblemDetail} because the contract fixes the
 * field names ({@code requestId}, {@code code}, {@code fieldErrors}) and they differ from RFC 9457.
 * Every failure path - controller advice, security entry point, access denied handler - must produce
 * exactly this shape so clients can rely on it (07-TC §22).
 *
 * @param timestamp   when the error was produced (UTC, ISO-8601)
 * @param requestId   the {@code X-Request-Id} of the failing request
 * @param status      HTTP status code
 * @param code        machine readable code {@code DOMAIN_REASON} (04-API §17)
 * @param message     human readable explanation, never a stack trace
 * @param fieldErrors validation details, empty for non-validation errors
 */
public record ApiError(
        Instant timestamp,
        String requestId,
        int status,
        String code,
        String message,
        List<FieldError> fieldErrors) {

    public ApiError {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }
}
