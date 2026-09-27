package com.opscenter.shared.infrastructure.redis;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A tiny circuit breaker shared by every Redis user of the backend: the lock, the rate limiter and
 * the service-resolution cache (blueprint D-50..D-53, review finding "Redis outage costs ~8 s per
 * webhook").
 * <p>
 * Why it is needed: all three callers already fall back when Redis fails (PostgreSQL advisory lock,
 * fail-open, read the database) - but <em>finding out</em> that Redis failed can take the full
 * command timeout (2 s) when Redis hangs instead of refusing connections, and a webhook makes several
 * Redis calls in a row. Without a breaker every one of them waits again, one delivery takes longer
 * than Alertmanager's 10 s webhook timeout, and Alertmanager retries forever.
 * <p>
 * How it works: the first {@code DataAccessException} of any caller opens the breaker for
 * {@code opscenter.redis.cool-down} (30 s). While it is open every caller skips Redis and goes
 * straight to its fallback - no network call at all. After the cool-down the next call simply tries
 * Redis again ("half open"): success keeps it closed, failure opens it for another cool-down.
 * <p>
 * It complements the Lettuce setting {@code disconnectedBehavior=REJECT_COMMANDS}
 * ({@link RedisClientConfig}), which makes calls fail immediately while the client <em>knows</em> the
 * connection is down; the breaker also covers the cases the client cannot know (a hung server, the
 * first connection attempt at start-up).
 */
@Component
public class RedisAvailability {

    private static final Logger log = LoggerFactory.getLogger(RedisAvailability.class);

    private final Clock clock;
    private final Duration coolDown;
    /** Epoch millis until which Redis is skipped; in the past = breaker closed. */
    private final AtomicLong skipUntil = new AtomicLong(Long.MIN_VALUE);

    public RedisAvailability(Clock clock, @Value("${opscenter.redis.cool-down:PT30S}") Duration coolDown) {
        this.clock = clock;
        this.coolDown = coolDown;
    }

    /** {@code false} while the breaker is open: skip Redis and use the fallback directly. */
    public boolean isAvailable() {
        return clock.millis() >= skipUntil.get();
    }

    /** Opens the breaker for the cool-down; logs once per transition, not per call. */
    public void markUnavailable(RuntimeException cause) {
        long now = clock.millis();
        long previous = skipUntil.getAndSet(now + coolDown.toMillis());
        if (previous <= now) {
            log.warn("Redis unavailable ({}); lock, rate limit and resolution cache use their fallbacks for {} s",
                    cause.getClass().getSimpleName(), coolDown.toSeconds());
        }
    }

    /** Remaining cool-down, zero when closed (for tests and diagnostics). */
    public Duration remainingCoolDown() {
        return Duration.ofMillis(Math.max(0, skipUntil.get() - clock.millis()));
    }
}
