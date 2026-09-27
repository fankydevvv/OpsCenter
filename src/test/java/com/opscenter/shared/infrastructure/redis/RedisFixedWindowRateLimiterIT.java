package com.opscenter.shared.infrastructure.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.opscenter.shared.application.ratelimit.RateLimitDecision;
import com.opscenter.shared.application.ratelimit.RateLimiter;
import com.opscenter.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** D-53 against a real Redis: fixed window counting, expiry of the counter key, and fail-open. */
class RedisFixedWindowRateLimiterIT extends AbstractIntegrationTest {

    @Autowired RateLimiter rateLimiter;
    @Autowired StringRedisTemplate redis;

    private static RedisAvailability available() {
        return new RedisAvailability(Clock.systemUTC(), Duration.ofSeconds(30));
    }

    @Test
    void countsHitsPerBucketAndWindow_andRejectsAboveTheLimit() {
        // A fixed clock: with the real one the four calls could straddle a minute boundary and the
        // counts would restart (review finding "flaky test").
        Instant t0 = Instant.parse("2026-09-27T10:00:30Z");
        RedisFixedWindowRateLimiter limiter = new RedisFixedWindowRateLimiter(redis, available(),
                Clock.fixed(t0, ZoneOffset.UTC));
        String bucket = "it-webhook:" + UUID.randomUUID();
        String otherBucket = "it-webhook:" + UUID.randomUUID();

        List<RateLimitDecision> decisions = List.of(
                limiter.tryAcquire(bucket, 3, Duration.ofMinutes(1)),
                limiter.tryAcquire(bucket, 3, Duration.ofMinutes(1)),
                limiter.tryAcquire(bucket, 3, Duration.ofMinutes(1)),
                limiter.tryAcquire(bucket, 3, Duration.ofMinutes(1)));

        assertThat(decisions).extracting(RateLimitDecision::allowed).containsExactly(true, true, true, false);
        assertThat(decisions).extracting(RateLimitDecision::count).containsExactly(1L, 2L, 3L, 4L);
        RateLimitDecision rejected = decisions.get(3);
        assertThat(rejected.degraded()).isFalse();
        assertThat(rejected.retryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(limiter.tryAcquire(otherBucket, 3, Duration.ofMinutes(1)).count())
                .as("buckets are independent").isEqualTo(1);

        // the counter key is named after the epoch minute and expires on its own (2 windows)
        long epochMinute = t0.toEpochMilli() / 60_000;
        String counterKey = RedisFixedWindowRateLimiter.KEY_PREFIX + bucket + ":" + epochMinute;
        assertThat(redis.opsForValue().get(counterKey)).isEqualTo("4");
        assertThat(redis.getExpire(counterKey, TimeUnit.SECONDS)).isBetween(1L, 120L);
    }

    @Test
    void theApplicationBean_countsAgainstTheSharedRedis() {
        String bucket = "it-bean:" + UUID.randomUUID();
        assertThat(rateLimiter.tryAcquire(bucket, 5, Duration.ofMinutes(1)).degraded()).isFalse();
    }

    @Test
    void aNewWindowStartsFromZero() {
        String bucket = "it-window:" + UUID.randomUUID();
        Instant t0 = Instant.parse("2026-09-27T10:00:59Z");
        RedisFixedWindowRateLimiter atEndOfMinute = new RedisFixedWindowRateLimiter(redis, available(), Clock.fixed(t0, ZoneOffset.UTC));
        RedisFixedWindowRateLimiter nextMinute = new RedisFixedWindowRateLimiter(redis, available(),
                Clock.fixed(t0.plusSeconds(2), ZoneOffset.UTC));

        assertThat(atEndOfMinute.tryAcquire(bucket, 1, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(atEndOfMinute.tryAcquire(bucket, 1, Duration.ofMinutes(1)).allowed()).isFalse();
        RateLimitDecision fresh = nextMinute.tryAcquire(bucket, 1, Duration.ofMinutes(1));
        assertThat(fresh.allowed()).isTrue();
        assertThat(fresh.count()).isEqualTo(1);
        assertThat(fresh.retryAfter()).isEqualTo(Duration.ofSeconds(59));
    }

    @Test
    void redisDown_failsOpen() {
        // every call on this template fails like a Redis that refuses connections
        StringRedisTemplate broken = mock(StringRedisTemplate.class, invocation -> {
            throw new RedisConnectionFailureException("refused");
        });
        RedisAvailability availability = available();
        RedisFixedWindowRateLimiter limiter = new RedisFixedWindowRateLimiter(broken, availability, Clock.systemUTC());

        RateLimitDecision decision = limiter.tryAcquire("webhook:alertmanager", 1, Duration.ofMinutes(1));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.degraded()).isTrue();
        // the breaker is open: the next decision does not even try Redis
        assertThat(availability.isAvailable()).isFalse();
        assertThat(limiter.tryAcquire("webhook:alertmanager", 1, Duration.ofMinutes(1)).degraded()).isTrue();
    }
}
