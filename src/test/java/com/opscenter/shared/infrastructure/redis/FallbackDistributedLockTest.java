package com.opscenter.shared.infrastructure.redis;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.opscenter.shared.application.lock.LockLease;
import com.opscenter.shared.application.lock.LockTimeoutException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-50/D-51 decision logic without infrastructure: Redis first, released after completion; a Redis
 * <em>failure</em> falls back to PostgreSQL and is counted; a busy lock is not a failure.
 */
class FallbackDistributedLockTest {

    private static final Duration TTL = Duration.ofSeconds(15);
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final RedisDistributedLock redis = mock(RedisDistributedLock.class);
    private final PostgresAdvisoryLock postgres = mock(PostgresAdvisoryLock.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RedisAvailability availability = new RedisAvailability(Clock.systemUTC(), Duration.ofSeconds(30));
    private final FallbackDistributedLock lock = new FallbackDistributedLock(redis, postgres, availability, meters,
            Clock.systemUTC());

    @BeforeEach
    void simulateTransaction() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void endTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void redisLock_isReleasedOnlyAfterTheTransactionCompleted() {
        when(redis.acquire("alert-group:abc", TTL, WAIT)).thenReturn("token-1");
        when(redis.release("alert-group:abc", "token-1")).thenReturn(true);

        LockLease lease = lock.lockForTransaction("alert-group:abc", TTL, WAIT);

        assertThat(lease).isEqualTo(new LockLease("alert-group:abc", LockLease.REDIS));
        verify(redis, never()).release(any(), any());
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        verify(redis).release("alert-group:abc", "token-1");
        assertThat(meters.find("opscenter.lock.acquire").tag("backend", "redis").timer().count()).isEqualTo(1);
    }

    @Test
    void redisDown_fallsBackToPostgres_andIsCounted() {
        when(redis.acquire(any(), any(), any())).thenThrow(new RedisConnectionFailureException("refused"));

        LockLease lease = lock.lockForTransaction("alert-group:abc", TTL, WAIT);

        assertThat(lease.backend()).isEqualTo(LockLease.POSTGRES);
        verify(postgres).acquireForTransaction("alert-group:abc", WAIT);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        assertThat(meters.counter("opscenter.lock.fallback").count()).isEqualTo(1.0);
    }

    @Test
    void afterARedisFailure_furtherLocksSkipRedis_forTheCoolDown() {
        // Review finding: with Redis down every lock paid the Redis timeout again (~2 s each).
        when(redis.acquire(any(), any(), any())).thenThrow(new RedisConnectionFailureException("refused"));

        lock.lockForTransaction("alert-group:a", TTL, WAIT);
        lock.lockForTransaction("alert-group:b", TTL, WAIT);
        lock.lockForTransaction("alert-group:c", TTL, WAIT);

        verify(redis, times(1)).acquire(any(), any(), any());
        verify(postgres, times(3)).acquireForTransaction(any(), eq(WAIT));
        assertThat(availability.isAvailable()).isFalse();
        assertThat(meters.counter("opscenter.lock.fallback").count()).isEqualTo(3.0);
    }

    @Test
    void busyLock_isATimeout_notAFallback() {
        when(redis.acquire(any(), any(), any())).thenThrow(new LockTimeoutException("alert-group:abc", WAIT));

        assertThatThrownBy(() -> lock.lockForTransaction("alert-group:abc", TTL, WAIT))
                .isInstanceOf(LockTimeoutException.class)
                .extracting("code").isEqualTo(LockTimeoutException.CODE);
        verify(postgres, never()).acquireForTransaction(any(), any());
    }

    @Test
    void severalKeys_areLockedInAscendingOrder() {
        when(redis.acquire(any(), eq(TTL), eq(WAIT))).thenReturn("t");

        List<LockLease> leases = lock.lockAllForTransaction(List.of("b", "a", "c", "a"), TTL, WAIT);

        assertThat(leases).extracting(LockLease::key).containsExactly("a", "b", "c");
    }

    @Test
    void outsideATransaction_isAProgrammingError() {
        TransactionSynchronizationManager.clearSynchronization();
        try {
            assertThatThrownBy(() -> lock.lockForTransaction("x", TTL, WAIT)).isInstanceOf(IllegalStateException.class);
        }
        finally {
            TransactionSynchronizationManager.initSynchronization();
        }
    }
}
