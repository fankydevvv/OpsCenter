package com.opscenter.identity.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.audit.domain.AuditAction;
import com.opscenter.identity.domain.LoginAttempt;
import com.opscenter.identity.domain.LoginFailureReason;
import com.opscenter.identity.infrastructure.persistence.LoginAttemptRepository;
import com.opscenter.shared.application.RequestContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code login_attempts} rows and the matching audit lines (TC-AUTH-001/002, TC-AUD-001).
 * <p>
 * Both methods <b>join</b> the login transaction ({@code MANDATORY}: calling them outside one is a
 * programming error). That is safe for a failed login because {@code AuthenticationService} does
 * not throw inside the transaction: it records the failure, commits, and only then answers 401
 * ("throw after commit", D-29). An earlier version used {@code REQUIRES_NEW} here, which opened a
 * second pooled connection while the login transaction still held its own - ten concurrent bad
 * passwords were enough to exhaust the default Hikari pool.
 */
@Service
public class LoginAttemptRecorder {

    private final LoginAttemptRepository attempts;
    private final AuditRecorder audit;
    private final RequestContext requestContext;
    private final Clock clock;

    public LoginAttemptRecorder(LoginAttemptRepository attempts, AuditRecorder audit, RequestContext requestContext,
                                Clock clock) {
        this.attempts = attempts;
        this.audit = audit;
        this.requestContext = requestContext;
        this.clock = clock;
    }

    /**
     * @param userId the matched user, or {@code null} when the login named nobody
     *               ({@code audit_logs.actor_id} is a foreign key, so an unknown user has no actor)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFailure(String login, UUID userId, LoginFailureReason reason) {
        String ip = requestContext.clientIp().orElse(null);
        attempts.save(LoginAttempt.failure(login, userId, reason, ip, clock.instant()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("login", login);
        details.put("reason", reason.name());
        audit.record(AuditAction.AUTH_LOGIN_FAILED, "User", userId, null, details, userId, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSuccess(String login, UUID userId, UUID sessionId) {
        String ip = requestContext.clientIp().orElse(null);
        attempts.save(LoginAttempt.success(login, userId, ip, clock.instant()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("login", login);
        details.put("sessionId", sessionId.toString());
        audit.record(AuditAction.AUTH_LOGIN_SUCCESS, "User", userId, null, details, userId, null);
    }
}
