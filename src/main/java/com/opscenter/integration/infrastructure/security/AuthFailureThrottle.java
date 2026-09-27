package com.opscenter.integration.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.opscenter.integration.application.IntegrationProperties;
import com.opscenter.shared.application.ratelimit.RateLimitDecision;
import com.opscenter.shared.application.ratelimit.RateLimiter;

import org.springframework.stereotype.Component;

/**
 * Brute-force brake of the webhook (blueprint D-53): more than
 * {@code auth-failure-limit-per-minute} (20) wrong tokens from one client address within a minute
 * and that address gets {@code 429} - <em>before</em> its token is even compared - until the minute
 * window ends.
 * <p>
 * Only failures are counted (in Redis, key {@code opscenter:ratelimit:webhook-authfail:<ip>:<minute>},
 * shared by all backend instances), so the real Alertmanager is never slowed down by its own traffic.
 * The "blocked until" decision is cached in this instance's memory: the check before the comparison
 * then costs no Redis round trip. A Redis outage makes the counter fail open (nothing is blocked).
 */
@Component
public class AuthFailureThrottle {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_TRACKED = 10_000;

    private final RateLimiter rateLimiter;
    private final int limit;
    private final Clock clock;
    private final Map<String, Instant> blockedUntil = new ConcurrentHashMap<>();

    public AuthFailureThrottle(RateLimiter rateLimiter, IntegrationProperties properties, Clock clock) {
        this.rateLimiter = rateLimiter;
        this.limit = properties.alertmanager().authFailureLimitPerMinute();
        this.clock = clock;
    }

    /** {@code true} while the address is blocked. */
    public boolean isBlocked(String clientIp) {
        Instant until = blockedUntil.get(key(clientIp));
        if (until == null) {
            return false;
        }
        if (!clock.instant().isBefore(until)) {
            blockedUntil.remove(key(clientIp), until);
            return false;
        }
        return true;
    }

    /** Seconds until the block of the address ends (for {@code Retry-After}); at least 1. */
    public long retryAfterSeconds(String clientIp) {
        Instant until = blockedUntil.get(key(clientIp));
        long seconds = until == null ? 0 : Duration.between(clock.instant(), until).toSeconds();
        return Math.max(1, seconds);
    }

    /**
     * Counts one failed authentication.
     *
     * @return {@code true} when this failure exceeded the limit (the caller answers 429)
     */
    public boolean recordFailure(String clientIp) {
        RateLimitDecision decision = rateLimiter.tryAcquire("webhook-authfail:" + key(clientIp), limit, WINDOW);
        if (decision.allowed()) {
            return false;
        }
        if (blockedUntil.size() >= MAX_TRACKED) {
            Instant now = clock.instant();
            blockedUntil.values().removeIf(until -> !now.isBefore(until));
        }
        Duration block = decision.retryAfter().isZero() || decision.retryAfter().isNegative()
                ? WINDOW : decision.retryAfter();
        blockedUntil.put(key(clientIp), clock.instant().plus(block));
        return true;
    }

    private static String key(String clientIp) {
        return clientIp == null ? "unknown" : clientIp;
    }
}
