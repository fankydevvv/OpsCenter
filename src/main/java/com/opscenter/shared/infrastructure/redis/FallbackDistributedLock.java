package com.opscenter.shared.infrastructure.redis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import com.opscenter.shared.application.lock.DistributedLock;
import com.opscenter.shared.application.lock.LockLease;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The {@link DistributedLock} the application uses: Redis first, PostgreSQL when Redis is down
 * (blueprint D-50, D-51 "defence in depth").
 * <ol>
 *   <li>Try {@link RedisDistributedLock}. On success register a transaction synchronisation that
 *       releases the Redis key in {@code afterCompletion} - i.e. <em>after</em> the commit, so the
 *       next holder reads committed data.</li>
 *   <li>If Redis throws a {@link DataAccessException} (connection refused, timeout ...) take a
 *       PostgreSQL advisory lock instead, which PostgreSQL releases at the end of the same
 *       transaction. A WARN is logged at most once a minute and the counter
 *       {@code opscenter.lock.fallback} is incremented, so the degradation is visible but does not
 *       flood the log.</li>
 *   <li>A {@code LockTimeoutException} (lock busy) is <em>not</em> a Redis failure and propagates.</li>
 *   <li>A Redis failure also opens the shared {@link RedisAvailability} breaker: for the next 30 s
 *       locks go straight to PostgreSQL without trying Redis first, so an outage costs one failed
 *       call, not one per lock (review finding "Redis outage costs ~8 s per webhook").</li>
 * </ol>
 * Metrics: {@code opscenter.lock.acquire} (timer, tag {@code backend}) and
 * {@code opscenter.lock.fallback} (counter) - 05-DEPLOY §15, blueprint §9.4.
 */
@Component
public class FallbackDistributedLock implements DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(FallbackDistributedLock.class);
    private static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

    private final RedisDistributedLock redisLock;
    private final PostgresAdvisoryLock postgresLock;
    private final RedisAvailability availability;
    private final MeterRegistry meters;
    private final Counter fallbackCounter;
    private final Clock clock;
    private final AtomicReference<Instant> lastWarning = new AtomicReference<>(Instant.EPOCH);

    public FallbackDistributedLock(RedisDistributedLock redisLock, PostgresAdvisoryLock postgresLock,
                                   RedisAvailability availability, MeterRegistry meters, Clock clock) {
        this.redisLock = redisLock;
        this.postgresLock = postgresLock;
        this.availability = availability;
        this.meters = meters;
        this.fallbackCounter = Counter.builder("opscenter.lock.fallback")
                .description("Locks granted by PostgreSQL because Redis was unavailable (D-51)")
                .register(meters);
        this.clock = clock;
    }

    @Override
    public LockLease lockForTransaction(String key, Duration ttl, Duration wait) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("lockForTransaction must be called inside a transaction");
        }
        long started = System.nanoTime();
        if (!availability.isAvailable()) {
            // Breaker open: Redis failed moments ago - do not pay its timeout again.
            return postgresFallback(key, wait, started);
        }
        String token;
        try {
            token = redisLock.acquire(key, ttl, wait);
        }
        catch (DataAccessException redisUnavailable) {
            availability.markUnavailable(redisUnavailable);
            warnThrottled(key, redisUnavailable);
            return postgresFallback(key, wait, started);
        }
        TransactionSynchronizationManager.registerSynchronization(new ReleaseAfterCompletion(key, token));
        record(LockLease.REDIS, started);
        return new LockLease(key, LockLease.REDIS);
    }

    private LockLease postgresFallback(String key, Duration wait, long startedNanos) {
        fallbackCounter.increment();
        postgresLock.acquireForTransaction(key, wait);
        record(LockLease.POSTGRES, startedNanos);
        return new LockLease(key, LockLease.POSTGRES);
    }

    private void record(String backend, long startedNanos) {
        Timer.builder("opscenter.lock.acquire")
                .description("Time spent waiting for a distributed lock")
                .tag("backend", backend)
                .register(meters)
                .record(Duration.ofNanos(System.nanoTime() - startedNanos));
    }

    private void warnThrottled(String key, DataAccessException cause) {
        Instant now = clock.instant();
        Instant previous = lastWarning.get();
        if (Duration.between(previous, now).compareTo(WARN_INTERVAL) >= 0 && lastWarning.compareAndSet(previous, now)) {
            log.warn("Redis unavailable ({}); lock '{}' falls back to a PostgreSQL advisory lock "
                    + "(further warnings suppressed for 1 min)", cause.getClass().getSimpleName(), key);
        }
    }

    /** Deletes our Redis key once the transaction is over; never lets a Redis error escape a completed transaction. */
    private final class ReleaseAfterCompletion implements TransactionSynchronization {

        private final String key;
        private final String token;

        private ReleaseAfterCompletion(String key, String token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public void afterCompletion(int status) {
            try {
                if (!redisLock.release(key, token)) {
                    log.warn("Lock '{}' had already expired before the transaction completed "
                            + "(TTL too short or transaction too long - R-23)", key);
                }
            }
            catch (DataAccessException ex) {
                // The key expires by itself (TTL); nothing else to do.
                availability.markUnavailable(ex);
                log.warn("Could not release Redis lock '{}': {}", key, ex.getClass().getSimpleName());
            }
        }
    }
}
