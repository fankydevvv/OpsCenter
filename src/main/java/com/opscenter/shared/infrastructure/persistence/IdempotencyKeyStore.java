package com.opscenter.shared.infrastructure.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction-boundary helper for {@code IdempotencyService}.
 * <p>
 * Claiming, failing and purging a key must be committed <b>independently</b> of the business
 * transaction ({@code REQUIRES_NEW}); completing it must commit <b>together</b> with the created
 * resource ({@code REQUIRED}, joining the caller). Spring's {@code @Transactional} only works
 * through the proxy of another bean, which is why these methods are not inside the service itself.
 */
@Component
public class IdempotencyKeyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyStore.class);

    private final IdempotencyKeyRepository repository;
    private final Clock clock;
    private final Duration ttl;

    public IdempotencyKeyStore(IdempotencyKeyRepository repository, Clock clock,
                               @Value("${opscenter.idempotency.ttl:PT24H}") Duration ttl) {
        this.repository = repository;
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * Inserts an {@code IN_PROGRESS} row. Throws {@code DataIntegrityViolationException} when the
     * key already exists (unique constraint), which the service treats as "duplicate request".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyKeyEntity insertInProgress(UUID integrationId, String key, String requestHash,
                                                 String resourceType) {
        return insertInProgress(integrationId, key, requestHash, resourceType, null);
    }

    /** @param keyTtl lifetime of this key; {@code null} = the default {@code opscenter.idempotency.ttl} (D-43) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyKeyEntity insertInProgress(UUID integrationId, String key, String requestHash,
                                                 String resourceType, Duration keyTtl) {
        IdempotencyKeyEntity entity = new IdempotencyKeyEntity(UUID.randomUUID(), integrationId, key,
                requestHash, resourceType, clock.instant(), clock.instant().plus(effective(keyTtl)));
        return repository.saveAndFlush(entity);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyKeyEntity> find(UUID integrationId, String key) {
        return integrationId == null
                ? repository.findByIntegrationIdIsNullAndIdempotencyKey(key)
                : repository.findByIntegrationIdAndIdempotencyKey(integrationId, key);
    }

    /** Joins the caller's transaction on purpose: key and resource commit (or roll back) together. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void markCompleted(UUID id, UUID resourceId, int responseCode) {
        repository.findById(id).ifPresent(k -> k.complete(resourceId, responseCode));
    }

    /** @param responseCode the HTTP status the client received for the failed attempt (03-DB §22) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, int responseCode, String reason) {
        log.debug("Idempotency key {} marked FAILED ({}): {}", id, responseCode, reason);
        repository.findById(id).ifPresent(k -> k.fail(responseCode));
    }

    /** @return {@code true} when this caller took the key over (see the 4-argument variant) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reclaim(UUID id, String requestHash, String resourceType) {
        return reclaim(id, requestHash, resourceType, null);
    }

    /**
     * Restarts a FAILED or expired key for a new attempt - but only if it is <em>still</em> FAILED or
     * expired at this moment (conditional {@code UPDATE}). Two identical retries that both found the
     * key free can therefore never both run the operation (review finding: the second one would count
     * an extra occurrence and overwrite {@code resource_id}).
     *
     * @param keyTtl lifetime of the reclaimed key; {@code null} = the default TTL
     * @return {@code true} when this caller owns the key now; {@code false} = somebody else was faster
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reclaim(UUID id, String requestHash, String resourceType, Duration keyTtl) {
        Instant now = clock.instant();
        return repository.reclaimIfFree(id, requestHash, resourceType, now.plus(effective(keyTtl)), now,
                IdempotencyStatus.IN_PROGRESS, IdempotencyStatus.FAILED) == 1;
    }

    private Duration effective(Duration keyTtl) {
        return keyTtl == null ? ttl : keyTtl;
    }

    /**
     * Retention (03-DB §25 "Idempotency key 1-30 days", §29): removes every key whose TTL elapsed.
     *
     * @return number of rows deleted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeExpired(Instant now) {
        return repository.deleteExpired(now);
    }
}
