package com.opscenter.shared.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@code idempotency_keys}.
 * <p>
 * Two finders exist because a derived query with a {@code null} argument would be rendered as
 * {@code integration_id = NULL}, which never matches in SQL; internal calls must use the
 * {@code IsNull} variant explicitly.
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {

    Optional<IdempotencyKeyEntity> findByIntegrationIdIsNullAndIdempotencyKey(String idempotencyKey);

    Optional<IdempotencyKeyEntity> findByIntegrationIdAndIdempotencyKey(UUID integrationId, String idempotencyKey);

    /**
     * Takes over a FAILED or expired key <b>atomically</b>: the {@code where} clause re-checks the
     * state inside the {@code UPDATE}, so of two requests that both saw the key free exactly one gets
     * row count 1 - the other must re-read the key (it is now IN_PROGRESS or COMPLETED).
     *
     * @return 1 when this caller owns the key now, 0 when somebody else took it first
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update IdempotencyKeyEntity k set k.status = :inProgress, k.requestHash = :requestHash, "
            + "k.resourceType = :resourceType, k.resourceId = null, k.responseCode = null, k.expiresAt = :expiresAt "
            + "where k.id = :id and (k.status = :failed or (k.expiresAt is not null and k.expiresAt <= :now))")
    int reclaimIfFree(@Param("id") UUID id, @Param("requestHash") String requestHash,
                      @Param("resourceType") String resourceType, @Param("expiresAt") Instant expiresAt,
                      @Param("now") Instant now, @Param("inProgress") IdempotencyStatus inProgress,
                      @Param("failed") IdempotencyStatus failed);

    /** Retention helper (03-DB §29): removes keys whose TTL elapsed. */
    @Modifying
    @Query("delete from IdempotencyKeyEntity k where k.expiresAt is not null and k.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
