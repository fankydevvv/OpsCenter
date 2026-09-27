package com.opscenter.shared.infrastructure.redis;

import java.time.Duration;

import com.opscenter.shared.application.lock.LockTimeoutException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * PostgreSQL fallback of the distributed lock (blueprint D-51): a <b>transaction-level advisory
 * lock</b>.
 * <p>
 * {@code pg_try_advisory_xact_lock(bigint)} takes an application-defined lock that PostgreSQL
 * releases automatically at COMMIT or ROLLBACK - no unlock call, no leak if the application dies.
 * The lock name is hashed to the required 64-bit number with {@code hashtextextended(text, 0)};
 * two different names may collide, which only means some unrelated requests wait for each other.
 * The {@code try} variant is polled with a short backoff so a caller can give up after
 * {@code wait} ({@code pg_advisory_xact_lock} would block without a limit).
 * <p>
 * {@link JdbcTemplate} joins the JPA transaction's connection (Spring's {@code JpaTransactionManager}
 * exposes it), so the lock really belongs to the business transaction.
 */
@Component
public class PostgresAdvisoryLock {

    private static final String SQL = "select pg_try_advisory_xact_lock(hashtextextended(?, 0))";

    private final JdbcTemplate jdbc;

    public PostgresAdvisoryLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws LockTimeoutException still held by another transaction after {@code wait} */
    public void acquireForTransaction(String key, Duration wait) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A PostgreSQL advisory lock needs an active transaction");
        }
        String lockName = RedisDistributedLock.KEY_PREFIX + key;
        long deadline = System.nanoTime() + wait.toNanos();
        long backoffMs = 25;
        while (true) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(SQL, Boolean.class, lockName))) {
                return;
            }
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMs <= 0) {
                throw new LockTimeoutException(key, wait);
            }
            try {
                Thread.sleep(Math.min(backoffMs, remainingMs));
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for an advisory lock", ex);
            }
            backoffMs = Math.min(backoffMs * 2, 200);
        }
    }
}
