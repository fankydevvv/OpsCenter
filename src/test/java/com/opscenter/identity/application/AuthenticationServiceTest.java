package com.opscenter.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.identity.domain.AuthenticationFailedException;
import com.opscenter.identity.domain.IdentityErrorCodes;
import com.opscenter.identity.domain.LoginFailureReason;
import com.opscenter.identity.domain.PasswordHasher;
import com.opscenter.identity.domain.RefreshToken;
import com.opscenter.identity.domain.TooManyLoginAttemptsException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-09 login semantics, branch by branch, with every collaborator mocked: the order of checks,
 * which failure reason is recorded, and that no session exists after a refused login
 * (TC-AUTH-002/003). The success path is checked for what it persists (04-API §16).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthenticationServiceTest {

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

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private AuthenticationService service;
    private User user;

    @BeforeEach
    void setUp() {
        when(passwordHasher.hash(anyString())).thenReturn("{bcrypt}dummy");
        when(tokens.refreshTtl()).thenReturn(Duration.ofDays(7));
        when(tokens.newOpaqueToken()).thenReturn("opaque");
        when(tokens.hash(anyString())).thenAnswer(inv -> "sha256:" + inv.getArgument(0));
        when(tokens.issueAccessToken(any(), any())).thenReturn(new AccessToken("jwt", 1800, NOW.plusSeconds(1800)));
        when(requestContext.clientIp()).thenReturn(Optional.of("10.0.0.7"));
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokens.save(any())).thenAnswer(inv -> inv.getArgument(0));

        user = User.register("engineer.a", "engineer.a@opscenter.local", "{bcrypt}real", "Engineer A");
        when(users.findByLogin("engineer.a")).thenReturn(Optional.of(user));
        when(users.findByLogin("engineer.a@opscenter.local")).thenReturn(Optional.of(user));

        service = new AuthenticationService(users, sessions, refreshTokens, passwordHasher, tokens, rateLimiter,
                attempts, revocation, audit, currentUser, requestContext, teamMemberships,
                TestTransactions.passThrough(), clock);
    }

    @Test
    void unknownLogin_answersGenericCode_burnsDummyCompare_andRecordsFailure() {
        when(users.findByLogin("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginCommand("ghost", "whatever", "ua")))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_INVALID_CREDENTIALS);

        verify(passwordHasher).matches("whatever", "{bcrypt}dummy");
        verify(attempts).recordFailure("ghost", null, LoginFailureReason.USER_NOT_FOUND);
        verify(sessions, never()).save(any());
    }

    @Test
    void TC_AUTH_002_wrongPassword_answersSameGenericCode_andRecordsBadCredentials() {
        when(passwordHasher.matches("wrong", "{bcrypt}real")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginCommand("engineer.a", "wrong", "ua")))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_INVALID_CREDENTIALS);

        verify(attempts).recordFailure("engineer.a", user.getId(), LoginFailureReason.BAD_CREDENTIALS);
        verify(sessions, never()).save(any());
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void TC_AUTH_003_lockedAccount_isRevealedOnlyAfterPasswordMatched_andCreatesNoSession() {
        user.lock();
        when(passwordHasher.matches("Engineer@123", "{bcrypt}real")).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginCommand("engineer.a", "Engineer@123", "ua")))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_ACCOUNT_LOCKED);

        verify(attempts).recordFailure("engineer.a", user.getId(), LoginFailureReason.ACCOUNT_LOCKED);
        verify(sessions, never()).save(any());
        assertThat(user.getLastLoginAt()).isNull();
    }

    @Test
    void TC_AUTH_003_disabledAccount_withWrongPassword_staysGeneric() {
        user.disable(NOW);
        when(passwordHasher.matches("wrong", "{bcrypt}real")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginCommand("engineer.a", "wrong", "ua")))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_INVALID_CREDENTIALS);
        verify(attempts).recordFailure("engineer.a", user.getId(), LoginFailureReason.BAD_CREDENTIALS);
    }

    @Test
    void TC_AUTH_003_disabledAccount_withRightPassword_answersAccountDisabled() {
        user.disable(NOW);
        when(passwordHasher.matches("Engineer@123", "{bcrypt}real")).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginCommand("engineer.a", "Engineer@123", "ua")))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_ACCOUNT_DISABLED);
        verify(attempts).recordFailure("engineer.a", user.getId(), LoginFailureReason.ACCOUNT_DISABLED);
    }

    @Test
    void rateLimited_isRefusedBeforeAnyLookup() {
        doThrow(new TooManyLoginAttemptsException(5, 15)).when(rateLimiter).assertNotBlocked("engineer.a");

        assertThatThrownBy(() -> service.login(new LoginCommand("Engineer.A", "x", "ua")))
                .isInstanceOf(TooManyLoginAttemptsException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_TOO_MANY_ATTEMPTS);

        verify(users, never()).findByLogin(anyString());
        verify(attempts, never()).recordFailure(anyString(), any(), any());
    }

    @Test
    void TC_AUTH_001_validLogin_opensSession_issuesTokens_andRecordsSuccess() {
        when(passwordHasher.matches("Engineer@123", "{bcrypt}real")).thenReturn(true);

        LoginResult result = service.login(new LoginCommand("Engineer.A@OpsCenter.local", "Engineer@123", "Mozilla"));

        ArgumentCaptor<UserSession> session = ArgumentCaptor.forClass(UserSession.class);
        verify(sessions).save(session.capture());
        assertThat(session.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(session.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(session.getValue().getIpAddress()).isEqualTo("10.0.0.7");
        assertThat(session.getValue().getUserAgent()).isEqualTo("Mozilla");

        ArgumentCaptor<RefreshToken> refresh = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(refresh.capture());
        assertThat(refresh.getValue().getSessionId()).isEqualTo(session.getValue().getId());
        assertThat(refresh.getValue().getTokenHash()).isEqualTo("sha256:opaque");

        verify(tokens).issueAccessToken(user, session.getValue().getId());
        verify(attempts).recordSuccess("engineer.a@opscenter.local", user.getId(), session.getValue().getId());
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);

        assertThat(result.accessToken()).isEqualTo("jwt");
        assertThat(result.refreshToken()).isEqualTo("opaque");
        assertThat(result.expiresIn()).isEqualTo(1800);
        assertThat(result.user().username()).isEqualTo("engineer.a");
        assertThat(result.user().roles()).isEqualTo(List.of());
    }

    @Test
    void logout_revokesSessionAndItsRefreshTokens_andAudits() {
        UUID sessionId = UUID.randomUUID();
        UserSession session = UserSession.open(user.getId(), "key", null, null, NOW.plusSeconds(3600), NOW);
        when(currentUser.sessionId()).thenReturn(Optional.of(sessionId));
        when(currentUser.actorId()).thenReturn(Optional.of(user.getId()));
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));

        service.logout();

        assertThat(session.getRevokedAt()).isEqualTo(NOW);
        verify(refreshTokens).revokeAllActiveBySession(sessionId, NOW);
        verify(audit).record(eq("AUTH_LOGOUT"), eq("UserSession"), eq(sessionId), isNull(), any(),
                eq(user.getId()), isNull());
    }

    @Test
    void logout_ofAlreadyRevokedSession_isIdempotent() {
        UUID sessionId = UUID.randomUUID();
        UserSession session = UserSession.open(user.getId(), "key", null, null, NOW.plusSeconds(3600), NOW);
        session.revoke(NOW.minusSeconds(10));
        when(currentUser.sessionId()).thenReturn(Optional.of(sessionId));
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));

        service.logout();

        verify(refreshTokens, never()).revokeAllActiveBySession(any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void me_returnsProfileWithTeamsFromThePort() {
        when(currentUser.actorId()).thenReturn(Optional.of(user.getId()));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(teamMemberships.membershipsOf(user.getId()))
                .thenReturn(List.of(new TeamMembershipView(UUID.randomUUID(), "PAYMENT", "Team Payment", "PRIMARY")));

        CurrentUserProfile profile = service.me();

        assertThat(profile.username()).isEqualTo("engineer.a");
        assertThat(profile.teams()).extracting(TeamMembershipView::code).containsExactly("PAYMENT");
    }
}
