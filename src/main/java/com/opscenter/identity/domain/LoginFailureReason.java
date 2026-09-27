package com.opscenter.identity.domain;

/**
 * Why a login attempt failed, stored in {@code login_attempts.failure_reason} for the audit trail
 * and for support staff. The client never sees these values directly: it receives the generic
 * {@code AUTH_INVALID_CREDENTIALS} for the first two (D-09, TC-AUTH-002).
 */
public enum LoginFailureReason {
    USER_NOT_FOUND,
    BAD_CREDENTIALS,
    ACCOUNT_LOCKED,
    ACCOUNT_DISABLED
}
