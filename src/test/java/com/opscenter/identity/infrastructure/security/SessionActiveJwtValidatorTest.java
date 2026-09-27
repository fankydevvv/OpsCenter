package com.opscenter.identity.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.identity.domain.UserSession;
import com.opscenter.identity.infrastructure.persistence.UserSessionRepository;
import com.opscenter.shared.infrastructure.security.JwtClaims;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** D-03: a token is only as alive as its session row; every failure uses the shared error code. */
@ExtendWith(MockitoExtension.class)
class SessionActiveJwtValidatorTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Mock UserSessionRepository sessions;

    private SessionActiveJwtValidator validator() {
        return new SessionActiveJwtValidator(sessions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Jwt jwtWithSid(String sid) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "HS256").subject("u")
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60));
        if (sid != null) {
            builder.claim(JwtClaims.SESSION_ID, sid);
        }
        return builder.build();
    }

    @Test
    void activeSession_passes() {
        UserSession session = UserSession.open(UUID.randomUUID(), "k", null, null, NOW.plusSeconds(600), NOW);
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));

        assertThat(validator().validate(jwtWithSid(session.getId().toString())).hasErrors()).isFalse();
    }

    @Test
    void TC_AUTH_005_revokedSession_failsWithSessionRevokedCode() {
        UserSession session = UserSession.open(UUID.randomUUID(), "k", null, null, NOW.plusSeconds(600), NOW);
        session.revoke(NOW);
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));

        OAuth2TokenValidatorResult result = validator().validate(jwtWithSid(session.getId().toString()));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting(OAuth2Error::getErrorCode).containsExactly(JwtClaims.ERROR_SESSION_REVOKED);
    }

    @Test
    void expiredSession_unknownSession_andMissingOrMalformedSid_allFail() {
        UserSession expired = UserSession.open(UUID.randomUUID(), "k", null, null, NOW.minusSeconds(1), NOW.minusSeconds(100));
        when(sessions.findById(expired.getId())).thenReturn(Optional.of(expired));
        UUID unknown = UUID.randomUUID();
        when(sessions.findById(unknown)).thenReturn(Optional.empty());

        assertThat(validator().validate(jwtWithSid(expired.getId().toString())).hasErrors()).isTrue();
        assertThat(validator().validate(jwtWithSid(unknown.toString())).hasErrors()).isTrue();
        assertThat(validator().validate(jwtWithSid(null)).hasErrors()).isTrue();
        assertThat(validator().validate(jwtWithSid("not-a-uuid")).hasErrors()).isTrue();
    }
}
