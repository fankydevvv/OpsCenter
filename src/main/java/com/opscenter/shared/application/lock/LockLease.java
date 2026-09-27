package com.opscenter.shared.application.lock;

/**
 * Proof that a {@link DistributedLock} was granted.
 *
 * @param key     the logical lock name the caller asked for
 * @param backend {@link #REDIS} normally, {@link #POSTGRES} when Redis was unreachable (D-51)
 */
public record LockLease(String key, String backend) {

    public static final String REDIS = "redis";
    public static final String POSTGRES = "postgres";
}
