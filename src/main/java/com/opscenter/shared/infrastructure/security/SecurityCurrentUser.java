package com.opscenter.shared.infrastructure.security;

import java.util.Optional;
import java.util.UUID;

import com.opscenter.shared.application.CurrentUser;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * {@link CurrentUser} backed by the {@code SecurityContext} of the current thread.
 * Only a {@link JwtAuthenticationToken} carries the {@code sub}/{@code sid} claims; any other
 * authentication (anonymous, test stubs) yields empty values so callers must handle "system".
 */
@Component
public class SecurityCurrentUser implements CurrentUser {

    @Override
    public Optional<UUID> actorId() {
        return jwtClaim(JwtClaims.SUBJECT).flatMap(SecurityCurrentUser::parseUuid);
    }

    @Override
    public Optional<UUID> sessionId() {
        return jwtClaim(JwtClaims.SESSION_ID).flatMap(SecurityCurrentUser::parseUuid);
    }

    @Override
    public Optional<String> username() {
        return jwtClaim(JwtClaims.USERNAME);
    }

    private static Optional<String> jwtClaim(String name) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth && jwtAuth.isAuthenticated()) {
            return Optional.ofNullable(jwtAuth.getToken().getClaimAsString(name));
        }
        return Optional.empty();
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        }
        catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
