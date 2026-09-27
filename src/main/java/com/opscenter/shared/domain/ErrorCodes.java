package com.opscenter.shared.domain;

/**
 * Error codes owned by the shared kernel (04-API §17 naming: {@code DOMAIN_REASON}).
 * <p>
 * Business modules define their own codes next to their exceptions (for example
 * {@code USER_NOT_FOUND} in identity); only the cross-cutting ones live here so that the
 * exception handler, the security handlers and the tests agree on a single spelling.
 */
public final class ErrorCodes {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String REQUEST_MALFORMED = "REQUEST_MALFORMED";
    /** Generic 4xx raised by the framework itself (e.g. a {@code ResponseStatusException}). */
    public static final String REQUEST_INVALID = "REQUEST_INVALID";
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";

    public static final String AUTH_UNAUTHENTICATED = "AUTH_UNAUTHENTICATED";
    public static final String AUTH_SESSION_REVOKED = "AUTH_SESSION_REVOKED";
    public static final String RBAC_PERMISSION_DENIED = "RBAC_PERMISSION_DENIED";

    public static final String CONCURRENCY_VERSION_CONFLICT = "CONCURRENCY_VERSION_CONFLICT";
    public static final String DATA_INTEGRITY_VIOLATION = "DATA_INTEGRITY_VIOLATION";

    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS";

    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    /** 503: PostgreSQL / RabbitMQ unreachable (04-API §17 "Dependency unavailable"). */
    public static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";

    private ErrorCodes() {
    }
}
