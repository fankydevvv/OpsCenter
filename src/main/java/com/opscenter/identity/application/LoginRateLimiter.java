package com.opscenter.identity.application;

import java.time.Clock;
import java.time.Duration;

import com.opscenter.identity.domain.TooManyLoginAttemptsException;
import com.opscenter.identity.infrastructure.persistence.LoginAttemptRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Brute-force brake for the login endpoint (D-09, 04-API §18 "rate limit cho auth").
 * <p>
 * Counts failed {@code login_attempts} for one login (username or email) inside a sliding window;
 * at {@code max-failures} the next attempt is refused with {@code 429 AUTH_TOO_MANY_ATTEMPTS}
 * before the password is even checked. Refused attempts are <em>not</em> recorded, so the block
 * lifts by itself once the oldest failure leaves the window. Counting in the database is enough
 * for a single instance (R-10); a Redis counter would replace this class for a cluster.
 */
@Component
public class LoginRateLimiter {

    private final LoginAttemptRepository attempts;
    private final Clock clock;
    private final int maxFailures;
    private final Duration window;

    public LoginRateLimiter(LoginAttemptRepository attempts, Clock clock,
                            @Value("${opscenter.security.login.max-failures:5}") int maxFailures,
                            @Value("${opscenter.security.login.window:PT15M}") Duration window) {
        this.attempts = attempts;
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.window = window;
    }

    /** @throws TooManyLoginAttemptsException when the login is currently blocked */
    public void assertNotBlocked(String normalizedLogin) {
        long failures = attempts.countFailuresSince(normalizedLogin, clock.instant().minus(window));
        if (failures >= maxFailures) {
            throw new TooManyLoginAttemptsException(maxFailures, window.toMinutes());
        }
    }
}
