package com.opscenter.identity.domain;

import com.opscenter.shared.domain.DomainException;

import org.springframework.http.HttpStatus;

/**
 * A login or refresh attempt was rejected -> HTTP 401 with an {@code AUTH_*} code (D-09).
 * <p>
 * This is a {@link DomainException}, <em>not</em> Spring Security's
 * {@code AuthenticationException}: the login endpoint is public, so the failure is a normal
 * business outcome rendered by {@code ApiExceptionHandler}, and the code tells the client what to
 * do next. Wrong password and unknown user share one generic code on purpose so an attacker cannot
 * enumerate accounts (TC-AUTH-002); LOCKED/DISABLED are only revealed after the password matched.
 */
public class AuthenticationFailedException extends DomainException {

    public AuthenticationFailedException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static AuthenticationFailedException invalidCredentials() {
        return new AuthenticationFailedException(IdentityErrorCodes.AUTH_INVALID_CREDENTIALS,
                "Invalid username/email or password");
    }

    public static AuthenticationFailedException accountLocked() {
        return new AuthenticationFailedException(IdentityErrorCodes.AUTH_ACCOUNT_LOCKED,
                "Account is locked; contact an administrator");
    }

    public static AuthenticationFailedException accountDisabled() {
        return new AuthenticationFailedException(IdentityErrorCodes.AUTH_ACCOUNT_DISABLED,
                "Account is disabled");
    }

    public static AuthenticationFailedException refreshTokenInvalid() {
        return new AuthenticationFailedException(IdentityErrorCodes.AUTH_REFRESH_TOKEN_INVALID,
                "Refresh token is invalid, expired or revoked; please sign in again");
    }
}
