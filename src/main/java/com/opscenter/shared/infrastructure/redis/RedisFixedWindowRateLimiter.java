package com.opscenter.shared.infrastructure.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.opscenter.shared.application.ratelimit.RateLimitDecision;
import com.opscenter.shared.application.ratelimit.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limiter in Redis (blueprint D-53).
 * <p>
 * Key {@code opscenter:ratelimit:<bucket>:<windowIndex>} where {@code windowIndex = epochMillis /
 * window}: for a one-minute window that is simply the epoch minute. Each request runs one Lua
 * script: {@code INCR} the counter and, for the first hit of the window, give the key an expiry of
 * two windows so old counters vanish on their own. Doing both in one script is atomic - with two
 * separate commands a crash in between would leave a counter without expiry.
 * <p>
 * A fixed window is the simplest limiter a reader can verify with {@code redis-cli}; its known
 * weakness (up to 2x the limit across a window boundary) is irrelevant for protecting a webhook.
 * <b>Fail-open</b>: any Redis error lets the request through ({@link RateLimitDecision#degraded()})
 * and opens the shared {@link RedisAvailability} breaker, so for the next 30 s the limiter does not
 * even try Redis (no timeout paid per request while Redis is down).
 */
@Component
public class RedisFixedWindowRateLimiter implements RateLimiter {

    public static final String KEY_PREFIX = "opscenter:ratelimit:";

    private static final Logger log = LoggerFactory.getLogger(RedisFixedWindowRateLimiter.class);
    private static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

    private static final RedisScript<Long> INCREMENT = RedisScript.of(
            "local count = redis.call('INCR', KEYS[1]) "
                    + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return count",
            Long.class);

    private final StringRedisTemplate redis;
    private final RedisAvailability availability;
    private final Clock clock;
    private final AtomicReference<Instant> lastWarning = new AtomicReference<>(Instant.EPOCH);

    public RedisFixedWindowRateLimiter(StringRedisTemplate redis, RedisAvailability availability, Clock clock) {
        this.redis = redis;
        this.availability = availability;
        this.clock = clock;
    }

    @Override
    public RateLimitDecision tryAcquire(String bucket, int limit, Duration window) {
        long nowMs = clock.millis();
        long windowMs = window.toMillis();
        long windowIndex = nowMs / windowMs;
        String key = KEY_PREFIX + bucket + ":" + windowIndex;
        if (!availability.isAvailable()) {
            return RateLimitDecision.failOpen(limit);
        }
        try {
            Long count = redis.execute(INCREMENT, List.of(key), String.valueOf(windowMs * 2));
            long hits = count == null ? 0 : count;
            Duration retryAfter = Duration.ofMillis((windowIndex + 1) * windowMs - nowMs);
            return new RateLimitDecision(hits <= limit, hits, limit, retryAfter, false);
        }
        catch (DataAccessException ex) {
            availability.markUnavailable(ex);
            warnThrottled(bucket, ex);
            return RateLimitDecision.failOpen(limit);
        }
    }

    private void warnThrottled(String bucket, DataAccessException cause) {
        Instant now = clock.instant();
        Instant previous = lastWarning.get();
        if (Duration.between(previous, now).compareTo(WARN_INTERVAL) >= 0 && lastWarning.compareAndSet(previous, now)) {
            log.warn("Redis unavailable ({}); rate limit '{}' fails open (further warnings suppressed for 1 min)",
                    cause.getClass().getSimpleName(), bucket);
        }
    }
}
