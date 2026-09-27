package com.opscenter.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.identity.domain.AuthenticationFailedException;
import com.opscenter.identity.domain.IdentityErrorCodes;
import com.opscenter.identity.domain.PasswordHasher;
import com.opscenter.identity.domain.RefreshToken;
import com.opscenter.identity.domain.User;
import com.opscenter.identity.domain.UserSession;
import com.opscenter.identity.infrastructure.persistence.RefreshTokenRepository;
import com.opscenter.identity.infrastructure.persistence.UserRepository;
import com.opscenter.identity.infrastructure.persistence.UserSessionRepository;
import com.opscenter.identity.infrastructure.security.AccessToken;
import com.opscenter.identity.infrastructure.security.JwtTokenService;
import com.opscenter.shared.application.CurrentUser;
import com.opscenter.shared.application.RequestContext;
import com.opscenter.support.TestTransactions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-02 refresh rotation and reuse detection (TC-AUTH-004): a presented token is consumed and
 * linked to its successor; presenting it twice revokes the whole session; expired tokens,
 * dead sessions and blocked accounts are refused. A token that was revoked by a logout (no
 * successor) is refused without raising the theft alarm.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshRotationTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Mock UserRepository users;
    @Mock UserSessionRepository sessions;
    @Mock RefreshTokenRepository refreshTokens;
    @Mock PasswordHasher passwordHasher;
    @Mock JwtTokenService tokens;
    @Mock LoginRateLimiter rateLimiter;
    @Mock LoginAttemptRecorder attempts;
    @Mock SessionRevocationService revocation;
    @Mock AuditRecorder audit;
    @Mock CurrentUser currentUser;
    @Mock RequestContext requestContext;
    @Mock TeamMembershipQuery teamMemberships;

    private AuthenticationService service;
    private User user;
    private UserSession session;
    private RefreshToken presented;

    @BeforeEach
    void setUp() {
        when(passwordHasher.hash(anyString())).thenReturn("{bcrypt}dummy");
        when(tokens.refreshTtl()).thenReturn(Duration.ofDays(7));
        when(tokens.newOpaqueToken()).thenReturn("next-opaque");
        when(tokens.hash(anyString())).thenAnswer(inv -> "sha256:" + inv.getArgument(0));
        when(tokens.issueAccessToken(any(), any())).thenReturn(new AccessToken("jwt-2", 1800, NOW.plusSeconds(1800)));
        when(refreshTokens.save(any())).thenAnswer(inv -> inv.getArgument(0));

        user = User.register("engineer.a", "engineer.a@opscenter.local", "{bcrypt}real", "Engineer A");
        session = UserSession.open(user.getId(), "key", null, null, NOW.plus(Duration.ofDays(1)), NOW.minusSeconds(60));
        presented = RefreshToken.issue(user.getId(), session.getId(), "sha256:old-opaque", NOW.plus(Duration.ofDays(1)),
                NOW.minusSeconds(60));
        when(refreshTokens.findByTokenHash("sha256:old-opaque")).thenReturn(Optional.of(presented));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        service = new AuthenticationService(users, sessions, refreshTokens, passwordHasher, tokens, rateLimiter,
                attempts, revocation, audit, currentUser, requestContext, teamMemberships,
                TestTransactions.passThrough(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void TC_AUTH_004_validRefresh_rotatesTokenAndExtendsSession() {
        LoginResult result = service.refresh("old-opaque");

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(saved.capture());
        assertThat(saved.getValue().getTokenHash()).isEqualTo("sha256:next-opaque");
        assertThat(saved.getValue().getSessionId()).isEqualTo(session.getId());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));

        assertThat(presented.isRevoked()).isTrue();
        assertThat(presented.getReplacedById()).isEqualTo(saved.getValue().getId());
        assertThat(session.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));

        assertThat(result.accessToken()).isEqualTo("jwt-2");
        assertThat(result.refreshToken()).isEqualTo("next-opaque");
        verify(tokens).issueAccessToken(user, session.getId());
        verify(revocation, never()).revokeSessionAfterTokenReuse(any());
    }

    @Test
    void reuseOfRotatedToken_revokesTheWholeSession() {
        presented.rotateTo(RefreshToken.issue(user.getId(), session.getId(), "sha256:x", NOW.plusSeconds(10), NOW), NOW);

        assertThatThrownBy(() -> service.refresh("old-opaque"))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_REFRESH_TOKEN_INVALID);

        verify(revocation).revokeSessionAfterTokenReuse(session.getId());
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void tokenRevokedByLogout_isRefusedWithoutTheReuseAlarm() {
        // revoked, but never rotated: no successor -> a stale token, not a stolen one
        presented.revoke(NOW.minusSeconds(5));

        assertThatThrownBy(() -> service.refresh("old-opaque"))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_REFRESH_TOKEN_INVALID);

        verify(revocation, never()).revokeSessionAfterTokenReuse(any());
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void unknownToken_isRefused() {
        assertThatThrownBy(() -> service.refresh("never-issued"))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_REFRESH_TOKEN_INVALID);
        verify(revocation, never()).revokeSessionAfterTokenReuse(any());
    }

    @Test
    void expiredToken_isRefusedWithoutRevokingTheSession() {
        RefreshToken expired = RefreshToken.issue(user.getId(), session.getId(), "sha256:expired", NOW.minusSeconds(1),
                NOW.minus(Duration.ofDays(8)));
        when(refreshTokens.findByTokenHash("sha256:expired")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.refresh("expired"))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_REFRESH_TOKEN_INVALID);
        verify(revocation, never()).revokeSessionAfterTokenReuse(any());
        assertThat(session.isActiveAt(NOW)).isTrue();
    }

    @Test
    void revokedSession_refusesRefresh() {
        session.revoke(NOW.minusSeconds(1));

        assertThatThrownBy(() -> service.refresh("old-opaque"))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_REFRESH_TOKEN_INVALID);
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void lockedUser_cannotRefresh() {
        user.lock();

        assertThatThrownBy(() -> service.refresh("old-opaque"))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_ACCOUNT_LOCKED);
        verify(refreshTokens, never()).save(any());
        assertThat(presented.isRevoked()).isFalse();
    }
}
