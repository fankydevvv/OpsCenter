package com.opscenter.shared.application.ratelimit;

import java.time.Duration;

/**
 * Answer of {@link RateLimiter#tryAcquire}.
 *
 * @param allowed    {@code false} = the caller should answer 429
 * @param count      hits counted in the current window including this one ({@code 0} when degraded)
 * @param limit      the limit that was applied
 * @param retryAfter time until the current window ends (for a {@code Retry-After} header)
 * @param degraded   the counter store was unavailable and the request was let through (fail-open)
 */
public record RateLimitDecision(boolean allowed, long count, int limit, Duration retryAfter, boolean degraded) {

    public static RateLimitDecision failOpen(int limit) {
        return new RateLimitDecision(true, 0, limit, Duration.ZERO, true);
    }
}
