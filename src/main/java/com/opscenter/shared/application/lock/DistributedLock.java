package com.opscenter.shared.application.lock;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Port for a short-lived mutual-exclusion lock shared by every backend instance (blueprint D-50, D-51).
 * <p>
 * Typical use: two Alertmanager deliveries of the same alert group arrive at the same moment; both
 * would read "no open incident yet" and create two. Holding the lock of the group's correlation key
 * while reading and writing serialises them. The lock is bound to the caller's <b>transaction</b>
 * and released only after it completed (commit or rollback), so the second request is guaranteed to
 * see what the first one committed.
 * <p>
 * The adapter uses Redis ({@code SET NX PX}) and falls back to a PostgreSQL advisory lock when Redis
 * is unreachable. A lock with a TTL is never an absolute guarantee (a very long GC pause can outlive
 * it), so correctness still comes from unique indexes in PostgreSQL - the lock only makes the race
 * rare and cheap (Redis is "short-lived coordination, not a source of truth", 05-DEPLOY §23.1).
 */
public interface DistributedLock {

    /**
     * Acquires the lock named {@code key} and keeps it until the current transaction completes.
     *
     * @param key  logical name, e.g. {@code alert-group:<correlationKey>}; the adapter adds its own prefix
     * @param ttl  safety expiry in case this process dies while holding the lock
     * @param wait how long to wait for another holder before giving up
     * @return which backend granted the lock (for logs and metrics)
     * @throws LockTimeoutException  the lock was still held by someone else after {@code wait}
     * @throws IllegalStateException called outside a transaction
     */
    LockLease lockForTransaction(String key, Duration ttl, Duration wait);

    /**
     * Locks several keys for the current transaction in <b>ascending order</b>. Two requests that
     * need the same keys therefore always take them in the same order and cannot deadlock.
     */
    default List<LockLease> lockAllForTransaction(Collection<String> keys, Duration ttl, Duration wait) {
        return new TreeSet<>(keys).stream().map(key -> lockForTransaction(key, ttl, wait)).toList();
    }
}
