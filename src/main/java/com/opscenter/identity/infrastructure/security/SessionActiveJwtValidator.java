package com.opscenter.identity.infrastructure.security;

import java.time.Clock;
import java.util.UUID;

import com.opscenter.identity.infrastructure.persistence.UserSessionRepository;
import com.opscenter.shared.infrastructure.security.JwtClaims;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Rejects access tokens whose login session no longer exists, was revoked or expired (D-03,
 * TC-AUTH-005).
 * <p>
 * A JWT is self-contained, so on its own a logout or an account lock could not invalidate it
 * before {@code exp}. This validator adds one indexed primary-key lookup per request: the shared
 * {@code JwtConfig} discovers every {@code OAuth2TokenValidator<Jwt>} bean and runs it after the
 * signature/expiry/issuer checks. Returning the {@code session_revoked} error code is what lets
 * {@code ApiAuthenticationEntryPoint} answer {@code 401 AUTH_SESSION_REVOKED} instead of the
 * generic {@code AUTH_UNAUTHENTICATED}, so the client knows a refresh will not help.
 */
@Component
public class SessionActiveJwtValidator implements OAuth2TokenValidator<Jwt> {

    private final UserSessionRepository sessions;
    private final Clock clock;

    public SessionActiveJwtValidator(UserSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String sid = jwt.getClaimAsString(JwtClaims.SESSION_ID);
        UUID sessionId = parse(sid);
        if (sessionId == null) {
            return failure("Access token carries no valid session id");
        }
        boolean active = sessions.findById(sessionId)
                .map(session -> session.isActiveAt(clock.instant()))
                .orElse(false);
        return active ? OAuth2TokenValidatorResult.success() : failure("Session " + sessionId + " is revoked or expired");
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(JwtClaims.ERROR_SESSION_REVOKED, description, null));
    }

    private static UUID parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
