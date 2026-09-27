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

    /** Retention helper (03-DB §29): removes keys whose TTL elapsed. */
    @Modifying
    @Query("delete from IdempotencyKeyEntity k where k.expiresAt is not null and k.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
