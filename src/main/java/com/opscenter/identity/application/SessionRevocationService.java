package com.opscenter.identity.application;

import java.time.Clock;
import java.util.UUID;

import com.opscenter.identity.infrastructure.persistence.RefreshTokenRepository;
import com.opscenter.identity.infrastructure.persistence.UserSessionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kills sessions and their refresh tokens together (D-02, D-03).
 * <p>
 * Both methods join the caller's transaction ({@code MANDATORY}):
 * <ul>
 *   <li>{@link #revokeAllForUser} is used by the account lock/delete, which must commit or roll
 *       back as one unit with the status change.</li>
 *   <li>{@link #revokeSessionAfterTokenReuse} is used by the refresh flow, which commits the
 *       revocation <em>before</em> answering 401 (throw after commit, D-29) - so no separate
 *       transaction is needed for the revocation to survive, and no second connection is held.</li>
 * </ul>
 */
@Service
public class SessionRevocationService {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocationService.class);

    private final UserSessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    public SessionRevocationService(UserSessionRepository sessions, RefreshTokenRepository refreshTokens,
                                    Clock clock) {
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAllForUser(UUID userId) {
        var now = clock.instant();
        int revokedSessions = sessions.revokeAllActiveByUser(userId, now);
        int revokedTokens = refreshTokens.revokeAllActiveByUser(userId, now);
        log.info("Revoked {} session(s) and {} refresh token(s) of user {}", revokedSessions, revokedTokens, userId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeSessionAfterTokenReuse(UUID sessionId) {
        var now = clock.instant();
        sessions.findById(sessionId).ifPresent(session -> session.revoke(now));
        int revokedTokens = refreshTokens.revokeAllActiveBySession(sessionId, now);
        log.warn("Refresh token reuse detected: revoked session {} and {} token(s)", sessionId, revokedTokens);
    }
}
