package com.opscenter.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.audit.domain.AuditAction;
import com.opscenter.identity.domain.AuthenticationFailedException;
import com.opscenter.identity.domain.IdentityErrorCodes;
import com.opscenter.identity.domain.LoginFailureReason;
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
import com.opscenter.shared.domain.NotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Login, refresh, logout and "who am I" (04-API §3, 01-SRS FR-IAM-01/03, blueprint §9, D-29).
 * <p>
 * The login flow follows D-09 step by step and the order matters:
 * <ol>
 *   <li>rate limit (429) - cheapest check, protects everything below;</li>
 *   <li>look the user up by username <em>or</em> email; an unknown login still runs one BCrypt
 *       comparison against a dummy hash so the response time does not reveal whether the account
 *       exists;</li>
 *   <li>compare the password - wrong password and unknown user both answer the generic
 *       {@code AUTH_INVALID_CREDENTIALS};</li>
 *   <li>only then check LOCKED/DISABLED (TC-AUTH-003) - the specific reason is revealed only to
 *       someone who already knows the password;</li>
 *   <li>open a session, issue refresh + access tokens, stamp {@code last_login_at}, record the
 *       attempt and the {@code AUTH_LOGIN_SUCCESS} audit line - all in this one transaction
 *       (04-API §16).</li>
 * </ol>
 * <b>Transaction pattern ("throw after commit"):</b> a refused login is a business answer, not a
 * crash, and the {@code login_attempts} / audit rows that prove it must survive. Instead of
 * throwing inside the transaction (which would roll those rows back and force a second,
 * {@code REQUIRES_NEW} transaction - two pooled connections per failed login, enough to exhaust
 * the pool under a small flood of bad passwords), the transactional part returns an
 * {@link Outcome}, the transaction commits, and only then is the exception thrown. One login =
 * one connection. The same pattern applies to {@link #refresh(String)}.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    /**
     * Hash of a random throw-away password, computed once at start-up. Comparing against it for
     * unknown logins burns the same CPU time as a real comparison, so an attacker cannot tell
     * "unknown user" from "wrong password" by measuring the response time.
     */
    private final String dummyPasswordHash;

    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher passwordHasher;
    private final JwtTokenService tokens;
    private final LoginRateLimiter rateLimiter;
    private final LoginAttemptRecorder attempts;
    private final SessionRevocationService revocation;
    private final AuditRecorder audit;
    private final CurrentUser currentUser;
    private final RequestContext requestContext;
    private final TeamMembershipQuery teamMemberships;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AuthenticationService(UserRepository users, UserSessionRepository sessions,
                                 RefreshTokenRepository refreshTokens, PasswordHasher passwordHasher,
                                 JwtTokenService tokens, LoginRateLimiter rateLimiter, LoginAttemptRecorder attempts,
                                 SessionRevocationService revocation, AuditRecorder audit, CurrentUser currentUser,
                                 RequestContext requestContext, TeamMembershipQuery teamMemberships,
                                 TransactionTemplate transactions, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.passwordHasher = passwordHasher;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.attempts = attempts;
        this.revocation = revocation;
        this.audit = audit;
        this.currentUser = currentUser;
        this.requestContext = requestContext;
        this.teamMemberships = teamMemberships;
        this.transactions = transactions;
        this.clock = clock;
        this.dummyPasswordHash = passwordHasher.hash(UUID.randomUUID().toString());
    }

    /**
     * What one transactional attempt produced: either a result, or the exception to throw once
     * the transaction (and the rows recording the failure) has been committed.
     */
    private record Outcome(LoginResult result, RuntimeException failure) {

        static Outcome success(LoginResult result) {
            return new Outcome(result, null);
        }

        static Outcome failure(RuntimeException failure) {
            return new Outcome(null, failure);
        }

        LoginResult unwrap() {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    public LoginResult login(LoginCommand command) {
        String login = User.normalize(command.login());
        rateLimiter.assertNotBlocked(login);
        return transactions.execute(status -> attemptLogin(login, command)).unwrap();
    }

    private Outcome attemptLogin(String login, LoginCommand command) {
        Optional<User> found = users.findByLogin(login);
        if (found.isEmpty()) {
            passwordHasher.matches(command.password(), dummyPasswordHash);
            attempts.recordFailure(login, null, LoginFailureReason.USER_NOT_FOUND);
            return Outcome.failure(AuthenticationFailedException.invalidCredentials());
        }
        User user = found.get();
        if (!passwordHasher.matches(command.password(), user.getPasswordHash())) {
            attempts.recordFailure(login, user.getId(), LoginFailureReason.BAD_CREDENTIALS);
            return Outcome.failure(AuthenticationFailedException.invalidCredentials());
        }
        try {
            user.assertCanAuthenticate();
        }
        catch (AuthenticationFailedException blocked) {
            LoginFailureReason reason = IdentityErrorCodes.AUTH_ACCOUNT_LOCKED.equals(blocked.code())
                    ? LoginFailureReason.ACCOUNT_LOCKED : LoginFailureReason.ACCOUNT_DISABLED;
            attempts.recordFailure(login, user.getId(), reason);
            return Outcome.failure(blocked);
        }

        Instant now = clock.instant();
        Instant sessionExpiry = now.plus(tokens.refreshTtl());
        UserSession session = UserSession.open(user.getId(), tokens.hash(tokens.newOpaqueToken()),
                requestContext.clientIp().orElse(null), command.userAgent(), sessionExpiry, now);
        sessions.save(session);

        String rawRefreshToken = tokens.newOpaqueToken();
        refreshTokens.save(RefreshToken.issue(user.getId(), session.getId(), tokens.hash(rawRefreshToken),
                sessionExpiry, now));

        user.recordLogin(now);
        attempts.recordSuccess(login, user.getId(), session.getId());
        AccessToken access = tokens.issueAccessToken(user, session.getId());
        log.info("User {} logged in (session {})", user.getUsername(), session.getId());
        return Outcome.success(new LoginResult(access.value(), rawRefreshToken, access.expiresInSeconds(),
                AuthenticatedUser.from(user)));
    }

    /**
     * Refresh with rotation and reuse detection (D-02, TC-AUTH-004). Roles and permissions are
     * read from the database again here, which is the moment a role change becomes effective
     * (D-07).
     * <p>
     * The presented token row is read with a pessimistic lock ({@code SELECT ... FOR UPDATE}), so
     * two concurrent refreshes with the same token are serialised: the first rotates, the second
     * then sees the rotated ({@code replaced_by_id} set) row and is treated as reuse. Without the
     * lock both would read "not revoked" and both would succeed, silently defeating D-02.
     */
    public LoginResult refresh(String rawRefreshToken) {
        String tokenHash = tokens.hash(rawRefreshToken);
        return transactions.execute(status -> rotate(tokenHash)).unwrap();
    }

    private Outcome rotate(String tokenHash) {
        Instant now = clock.instant();
        Optional<RefreshToken> found = refreshTokens.findByTokenHash(tokenHash);
        if (found.isEmpty()) {
            return Outcome.failure(AuthenticationFailedException.refreshTokenInvalid());
        }
        RefreshToken presented = found.get();
        if (presented.isRevoked()) {
            if (presented.getReplacedById() != null) {
                // Rotated away and presented again: two parties hold the same token. Assume theft
                // and kill the whole session; the revocation commits before the 401 is thrown.
                revocation.revokeSessionAfterTokenReuse(presented.getSessionId());
            }
            // Otherwise the token died with a logout or an account lock - stale, but not suspicious.
            return Outcome.failure(AuthenticationFailedException.refreshTokenInvalid());
        }
        if (presented.isExpiredAt(now)) {
            return Outcome.failure(AuthenticationFailedException.refreshTokenInvalid());
        }
        Optional<UserSession> activeSession = sessions.findById(presented.getSessionId())
                .filter(s -> s.isActiveAt(now));
        if (activeSession.isEmpty()) {
            return Outcome.failure(AuthenticationFailedException.refreshTokenInvalid());
        }
        Optional<User> owner = users.findById(presented.getUserId());
        if (owner.isEmpty()) {
            return Outcome.failure(AuthenticationFailedException.refreshTokenInvalid());
        }
        UserSession session = activeSession.get();
        User user = owner.get();
        user.assertCanAuthenticate();

        Instant newExpiry = now.plus(tokens.refreshTtl());
        String rawNext = tokens.newOpaqueToken();
        RefreshToken next = refreshTokens.save(
                RefreshToken.issue(user.getId(), session.getId(), tokens.hash(rawNext), newExpiry, now));
        presented.rotateTo(next, now);
        session.extendTo(newExpiry);

        AccessToken access = tokens.issueAccessToken(user, session.getId());
        return Outcome.success(new LoginResult(access.value(), rawNext, access.expiresInSeconds(),
                AuthenticatedUser.from(user)));
    }

    /**
     * Revokes the caller's own session (from the JWT {@code sid}) and its refresh tokens; the next
     * request with the same access token is rejected by {@code SessionActiveJwtValidator}
     * (TC-AUTH-005). Idempotent: logging out twice is not an error.
     */
    @Transactional
    public void logout() {
        UUID sessionId = currentUser.sessionId()
                .orElseThrow(() -> new IllegalStateException("logout requires an authenticated session"));
        UUID actorId = currentUser.actorId().orElse(null);
        Instant now = clock.instant();
        sessions.findById(sessionId)
                .filter(session -> session.isActiveAt(now))
                .ifPresent(session -> {
                    session.revoke(now);
                    refreshTokens.revokeAllActiveBySession(sessionId, now);
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("sessionId", sessionId.toString());
                    audit.record(AuditAction.AUTH_LOGOUT, "UserSession", sessionId, null, details, actorId, null);
                    log.info("Session {} logged out", sessionId);
                });
    }

    @Transactional(readOnly = true)
    public CurrentUserProfile me() {
        UUID userId = currentUser.actorId()
                .orElseThrow(() -> new IllegalStateException("me requires an authenticated user"));
        User user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException(IdentityErrorCodes.USER_NOT_FOUND,
                        "User " + userId + " not found"));
        return CurrentUserProfile.from(user, teamMemberships.membershipsOf(userId));
    }
}
