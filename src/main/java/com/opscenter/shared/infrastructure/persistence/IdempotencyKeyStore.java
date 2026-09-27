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
        IdempotencyKeyEntity entity = new IdempotencyKeyEntity(UUID.randomUUID(), integrationId, key,
                requestHash, resourceType, clock.instant(), clock.instant().plus(ttl));
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reclaim(UUID id, String requestHash, String resourceType) {
        repository.findById(id).ifPresent(k -> k.restart(requestHash, resourceType, clock.instant().plus(ttl)));
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
