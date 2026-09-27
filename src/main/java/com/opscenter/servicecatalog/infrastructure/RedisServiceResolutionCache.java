package com.opscenter.servicecatalog.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.opscenter.servicecatalog.application.ServiceCatalogProperties;
import com.opscenter.servicecatalog.application.ServiceResolutionCache;
import com.opscenter.shared.infrastructure.redis.RedisAvailability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis adapter of {@link ServiceResolutionCache} (blueprint D-52).
 * <p>
 * Layout, readable with {@code redis-cli}:
 * <pre>
 *   HASH opscenter:svc-resolve:v1:{orgId}:{serviceCode}      (TTL 10 min)
 *        field DEV          -> "serviceId|serviceEnvironmentId|owningTeamId|serviceName"
 *        field PRODUCTION   -> "serviceId|-|owningTeamId|serviceName"   (environment not registered)
 *        field _NONE_       -> ...                                      (alert without environment label)
 *   value "NONE"            -> no active service with that code (negative cache)
 * </pre>
 * One hash per service code makes invalidation a single {@code DEL}, whatever environments were
 * cached. The {@code v1} segment lets a future format change ignore old entries. The explicit
 * {@link StringRedisTemplate} (instead of {@code @Cacheable} + serializers) keeps the stored format
 * visible in code.
 * <p>
 * Every Redis error is swallowed (logged at most once a minute): a broken cache means "read the
 * database", never a failed alert. The error also opens the shared {@link RedisAvailability} breaker,
 * so while Redis is down reads and writes are skipped without paying a timeout each.
 * <p>
 * <b>Negative entries live shorter.</b> A "no such service" ({@code NONE}) answer is kept for
 * {@code negative-cache-ttl} (30 s), not the full TTL: a lookup that read "none" just before an
 * administrator registered the service can write its stale answer <em>after</em> the eviction of
 * that change, and alerts of the brand-new service would then stay UNMAPPED for ten minutes.
 */
@Component
public class RedisServiceResolutionCache implements ServiceResolutionCache {

    public static final String KEY_PREFIX = "opscenter:svc-resolve:v1:";
    static final String NO_ENVIRONMENT_FIELD = "_NONE_";
    static final String NONE = "NONE";
    private static final String NULL = "-";

    private static final Logger log = LoggerFactory.getLogger(RedisServiceResolutionCache.class);
    private static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final RedisAvailability availability;
    private final boolean enabled;
    private final Duration ttl;
    private final Duration negativeTtl;
    private final Clock clock;
    private final AtomicReference<Instant> lastWarning = new AtomicReference<>(Instant.EPOCH);

    public RedisServiceResolutionCache(StringRedisTemplate redis, RedisAvailability availability,
                                       ServiceCatalogProperties properties, Clock clock) {
        this.redis = redis;
        this.availability = availability;
        this.enabled = properties.resolution().cacheEnabled();
        this.ttl = properties.resolution().cacheTtl();
        this.negativeTtl = properties.resolution().negativeCacheTtl();
        this.clock = clock;
    }

    public static String key(UUID organizationId, String serviceCode) {
        return KEY_PREFIX + organizationId + ":" + serviceCode;
    }

    @Override
    public Optional<Entry> get(UUID organizationId, String serviceCode, String environment) {
        if (!enabled || !availability.isAvailable()) {
            return Optional.empty();
        }
        try {
            Object value = redis.opsForHash().get(key(organizationId, serviceCode), field(environment));
            return value == null ? Optional.empty() : Optional.of(decode(value.toString()));
        }
        catch (DataAccessException ex) {
            availability.markUnavailable(ex);
            warnThrottled("read", ex);
            return Optional.empty();
        }
        catch (IllegalArgumentException ex) {
            warnThrottled("read", ex);
            return Optional.empty();
        }
    }

    @Override
    public void put(UUID organizationId, String serviceCode, String environment, Entry entry) {
        if (!enabled || !availability.isAvailable()) {
            return;
        }
        String key = key(organizationId, serviceCode);
        try {
            redis.opsForHash().put(key, field(environment), encode(entry));
            // A negative answer only for a short time (see class comment); a positive one keeps the full TTL.
            redis.expire(key, entry.mapped() ? ttl : negativeTtl);
        }
        catch (DataAccessException ex) {
            availability.markUnavailable(ex);
            warnThrottled("write", ex);
        }
    }

    @Override
    public void evict(UUID organizationId, String serviceCode) {
        try {
            redis.delete(key(organizationId, serviceCode));
        }
        catch (DataAccessException ex) {
            // The entry expires by itself within the TTL; the next change retries the delete.
            availability.markUnavailable(ex);
            warnThrottled("evict", ex);
        }
    }

    static String field(String environment) {
        return environment == null ? NO_ENVIRONMENT_FIELD : environment;
    }

    static String encode(Entry entry) {
        if (!entry.mapped()) {
            return NONE;
        }
        return entry.serviceId() + "|" + orDash(entry.serviceEnvironmentId()) + "|" + orDash(entry.owningTeamId())
                + "|" + (entry.serviceName() == null ? "" : entry.serviceName());
    }

    static Entry decode(String value) {
        if (NONE.equals(value)) {
            return Entry.NONE;
        }
        // limit 4: the service name is last and may itself contain '|'
        String[] parts = value.split("\\|", 4);
        if (parts.length < 4) {
            throw new IllegalArgumentException("Unexpected cache value format");
        }
        return new Entry(UUID.fromString(parts[0]), uuidOrNull(parts[1]), uuidOrNull(parts[2]),
                parts[3].isEmpty() ? null : parts[3]);
    }

    private static String orDash(UUID id) {
        return id == null ? NULL : id.toString();
    }

    private static UUID uuidOrNull(String value) {
        return NULL.equals(value) ? null : UUID.fromString(value);
    }

    private void warnThrottled(String operation, RuntimeException cause) {
        Instant now = clock.instant();
        Instant previous = lastWarning.get();
        if (Duration.between(previous, now).compareTo(WARN_INTERVAL) >= 0 && lastWarning.compareAndSet(previous, now)) {
            log.warn("Service resolution cache {} failed ({}); falling back to PostgreSQL "
                    + "(further warnings suppressed for 1 min)", operation, cause.getClass().getSimpleName());
        }
    }
}
