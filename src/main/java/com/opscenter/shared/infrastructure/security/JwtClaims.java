package com.opscenter.shared.infrastructure.security;

/**
 * Names of the custom claims carried by an OpsCenter access token (D-01) and the validator error
 * code the identity module uses to signal a revoked session (D-03).
 * <p>
 * Shared between the token issuer (identity's {@code TokenService}), the authentication converter
 * and the entry point so a claim name is spelled once.
 */
public final class JwtClaims {

    /** Standard {@code sub}: user id (UUID). */
    public static final String SUBJECT = "sub";
    /** Login session id (UUID) - checked against {@code user_sessions} on every request. */
    public static final String SESSION_ID = "sid";
    public static final String USERNAME = "username";
    /** Role codes, e.g. {@code ["ADMIN"]}; exposed as {@code ROLE_ADMIN} authorities. */
    public static final String ROLES = "roles";
    /** Permission codes, e.g. {@code ["user.read"]}; exposed verbatim as authorities. */
    public static final String PERMISSIONS = "permissions";

    /**
     * {@code OAuth2Error} code an {@code OAuth2TokenValidator<Jwt>} returns when the {@code sid}
     * session was revoked (logout, lock). The entry point maps it to {@code AUTH_SESSION_REVOKED}.
     */
    public static final String ERROR_SESSION_REVOKED = "session_revoked";

    private JwtClaims() {
    }
}
