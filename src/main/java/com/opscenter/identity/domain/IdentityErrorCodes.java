package com.opscenter.identity.domain;

/**
 * Error codes of the identity module (04-API §17 naming {@code DOMAIN_REASON}).
 * <p>
 * Kept as constants in one place so the services that throw them, the exception handler that
 * renders them and the tests that assert them cannot drift apart in spelling.
 */
public final class IdentityErrorCodes {

    // --- authentication (401 / 429) ---------------------------------------------------------
    public static final String AUTH_INVALID_CREDENTIALS = "AUTH_INVALID_CREDENTIALS";
    public static final String AUTH_ACCOUNT_LOCKED = "AUTH_ACCOUNT_LOCKED";
    public static final String AUTH_ACCOUNT_DISABLED = "AUTH_ACCOUNT_DISABLED";
    public static final String AUTH_TOO_MANY_ATTEMPTS = "AUTH_TOO_MANY_ATTEMPTS";
    public static final String AUTH_REFRESH_TOKEN_INVALID = "AUTH_REFRESH_TOKEN_INVALID";

    // --- users ------------------------------------------------------------------------------
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String USER_USERNAME_TAKEN = "USER_USERNAME_TAKEN";
    public static final String USER_EMAIL_TAKEN = "USER_EMAIL_TAKEN";
    public static final String USER_CANNOT_LOCK_SELF = "USER_CANNOT_LOCK_SELF";
    public static final String USER_CANNOT_DELETE_SELF = "USER_CANNOT_DELETE_SELF";
    public static final String USER_NOT_ACTIVE = "USER_NOT_ACTIVE";
    public static final String USER_NOT_LOCKED = "USER_NOT_LOCKED";

    // --- roles & permissions ----------------------------------------------------------------
    public static final String ROLE_NOT_FOUND = "ROLE_NOT_FOUND";
    public static final String ROLE_CODE_TAKEN = "ROLE_CODE_TAKEN";
    public static final String PERMISSION_NOT_FOUND = "PERMISSION_NOT_FOUND";

    private IdentityErrorCodes() {
    }
}
