package com.opscenter.shared.application.ratelimit;

import java.time.Duration;

/**
 * Port for counting requests per bucket in fixed time windows (01-SRS §18, 04-API §18 "rate limit
 * inbound integration"; blueprint D-53).
 * <p>
 * Implementations are <b>fail-open</b>: if the counter store (Redis) is down the request is
 * allowed and the decision is flagged {@link RateLimitDecision#degraded()}. Blocking real alerts
 * because a cache is broken would be worse than letting a burst through.
 */
public interface RateLimiter {

    /**
     * Counts one hit for {@code bucket} in the current window and says whether it is still within
     * {@code limit}.
     *
     * @param bucket logical name, e.g. {@code webhook:alertmanager} or {@code webhook-authfail:10.0.0.7}
     * @param limit  maximum hits per window
     * @param window window length; the window index is {@code epochMillis / window}
     */
    RateLimitDecision tryAcquire(String bucket, int limit, Duration window);
}
