package com.opscenter.shared.infrastructure.redis;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.opscenter.shared.application.lock.DistributedLock;
import com.opscenter.shared.application.lock.LockLease;
import com.opscenter.shared.application.lock.LockTimeoutException;
import com.opscenter.support.AbstractIntegrationTest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Blueprint D-50/D-51 against a real Redis 7 and PostgreSQL 17: {@code SET NX PX} semantics,
 * owner-token release, TTL expiry, wait timeout, "held until commit", and the advisory-lock
 * fallback serialising two transactions when Redis is unavailable.
 */
class RedisDistributedLockIT extends AbstractIntegrationTest {

    @Autowired RedisDistributedLock redisLock;
    @Autowired PostgresAdvisoryLock postgresLock;
    @Autowired DistributedLock distributedLock;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate tx;

    private static String key() {
        return "it:" + UUID.randomUUID();
    }

    @Test
    void setNx_grantsTheLockOnce_andOnlyTheOwnerTokenReleasesIt() {
        String key = key();

        Optional<String> first = redisLock.tryAcquire(key, Duration.ofSeconds(10));
        assertThat(first).isPresent();
        assertThat(redisLock.tryAcquire(key, Duration.ofSeconds(10))).isEmpty();
        Long ttlMs = redis.getExpire(RedisDistributedLock.KEY_PREFIX + key, TimeUnit.MILLISECONDS);
        assertThat(ttlMs).isBetween(1L, 10_000L);

        assertThat(redisLock.release(key, "not-my-token")).isFalse();
        assertThat(redis.hasKey(RedisDistributedLock.KEY_PREFIX + key)).isTrue();
        assertThat(redisLock.release(key, first.get())).isTrue();
        assertThat(redisLock.tryAcquire(key, Duration.ofSeconds(10))).isPresent();
    }

    @Test
    void ttl_freesTheLockOfADeadHolder() throws Exception {
        String key = key();
        assertThat(redisLock.tryAcquire(key, Duration.ofMillis(200))).isPresent();

        Thread.sleep(400);

        assertThat(redisLock.tryAcquire(key, Duration.ofSeconds(5))).isPresent();
    }

    @Test
    void acquire_waitsWithBackoff_thenTimesOut() {
        String key = key();
        redisLock.tryAcquire(key, Duration.ofSeconds(10));

        long started = System.nanoTime();
        assertThatThrownBy(() -> redisLock.acquire(key, Duration.ofSeconds(10), Duration.ofMillis(300)))
                .isInstanceOf(LockTimeoutException.class);
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(300);
    }

    @Test
    void transactionalLock_isHeldUntilCommit_thenTheKeyIsGone() throws Exception {
        String key = key();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<LockLease> holder = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            LockLease lease = distributedLock.lockForTransaction(key, Duration.ofSeconds(15), Duration.ofSeconds(1));
            locked.countDown();
            await(release);
            return lease;
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        // a second transaction cannot get it while the first one is still running
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                distributedLock.lockForTransaction(key, Duration.ofSeconds(15), Duration.ofMillis(200))))
                .isInstanceOf(LockTimeoutException.class);
        assertThat(redis.hasKey(RedisDistributedLock.KEY_PREFIX + key)).isTrue();

        release.countDown();
        assertThat(holder.get(5, TimeUnit.SECONDS).backend()).isEqualTo(LockLease.REDIS);
        assertThat(redis.hasKey(RedisDistributedLock.KEY_PREFIX + key)).as("released after commit").isFalse();
        tx.executeWithoutResult(status -> assertThat(
                distributedLock.lockForTransaction(key, Duration.ofSeconds(15), Duration.ofMillis(200)).backend())
                .isEqualTo(LockLease.REDIS));
    }

    @Test
    void redisDown_advisoryLockSerialisesTransactions() throws Exception {
        RedisDistributedLock brokenRedis = mock(RedisDistributedLock.class);
        when(brokenRedis.acquire(any(), any(), any())).thenThrow(new RedisConnectionFailureException("refused"));
        FallbackDistributedLock lock = new FallbackDistributedLock(brokenRedis, postgresLock,
                new RedisAvailability(Clock.systemUTC(), Duration.ofSeconds(30)), new SimpleMeterRegistry(),
                Clock.systemUTC());
        String key = key();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<LockLease> holder = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            LockLease lease = lock.lockForTransaction(key, Duration.ofSeconds(15), Duration.ofSeconds(1));
            locked.countDown();
            await(release);
            return lease;
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                lock.lockForTransaction(key, Duration.ofSeconds(15), Duration.ofMillis(300))))
                .isInstanceOf(LockTimeoutException.class);

        release.countDown();
        assertThat(holder.get(5, TimeUnit.SECONDS).backend()).isEqualTo(LockLease.POSTGRES);
        // PostgreSQL released the advisory lock at COMMIT - no unlock call needed
        tx.executeWithoutResult(status -> assertThat(
                lock.lockForTransaction(key, Duration.ofSeconds(15), Duration.ofMillis(300)).backend())
                .isEqualTo(LockLease.POSTGRES));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
