package com.opscenter.shared.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.shared.application.lock.LockTimeoutException;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis half of the distributed lock (blueprint D-50): the classic single-instance Redis lock.
 * <ul>
 *   <li><b>Acquire</b> = {@code SET opscenter:lock:<key> <token> NX PX <ttl>}: succeeds only if the
 *       key does not exist, and the key expires by itself if this process dies (no eternal lock).</li>
 *   <li><b>Release</b> = a Lua script that deletes the key <em>only if it still holds our token</em>.
 *       A plain {@code DEL} could remove somebody else's lock after ours expired; Lua runs atomically
 *       inside Redis, so "compare and delete" cannot be interleaved.</li>
 *   <li><b>Wait</b> = retry with a small exponential backoff (25 ms doubling up to 200 ms) until the
 *       deadline, then {@link LockTimeoutException}.</li>
 * </ul>
 * Redis errors are <em>not</em> caught here: {@code FallbackDistributedLock} decides what to do
 * when Redis is down (D-51). Keys are written with {@link StringRedisTemplate} so they can be read
 * with {@code redis-cli} during the demo.
 */
@Component
public class RedisDistributedLock {

    /** Namespace of every lock key; together with the caller's key: {@code opscenter:lock:alert-group:<hash>}. */
    public static final String KEY_PREFIX = "opscenter:lock:";

    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(25);
    private static final Duration MAX_BACKOFF = Duration.ofMillis(200);

    private static final RedisScript<Long> RELEASE_IF_OWNER = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** One attempt: the owner token when the lock was free, empty when someone holds it. */
    public Optional<String> tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(KEY_PREFIX + key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    /**
     * Retries {@link #tryAcquire} until {@code wait} elapsed.
     *
     * @return the owner token needed by {@link #release}
     * @throws LockTimeoutException still held by someone else at the deadline
     */
    public String acquire(String key, Duration ttl, Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        long backoffMs = INITIAL_BACKOFF.toMillis();
        while (true) {
            Optional<String> token = tryAcquire(key, ttl);
            if (token.isPresent()) {
                return token.get();
            }
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMs <= 0) {
                throw new LockTimeoutException(key, wait);
            }
            sleep(Math.min(backoffMs, remainingMs));
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF.toMillis());
        }
    }

    /** @return {@code true} if our lock was deleted, {@code false} if it had expired or belongs to someone else */
    public boolean release(String key, String token) {
        Long deleted = redis.execute(RELEASE_IF_OWNER, List.of(KEY_PREFIX + key), token);
        return deleted != null && deleted > 0;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a lock", ex);
        }
    }
}
