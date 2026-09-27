package com.opscenter.integration.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.integration.domain.IntegrationSource;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code integration_sources} (03-DB §40.1). */
public interface IntegrationSourceRepository extends JpaRepository<IntegrationSource, UUID> {

    Optional<IntegrationSource> findByCode(String code);

    /**
     * Moves {@code last_event_at} forward (never backwards) without touching {@code version}: it is
     * telemetry ("last synchronization", 01-SRS §14), not a change anybody edits concurrently.
     */
    @Modifying
    @Query(value = "update integration_sources set last_event_at = :at "
            + "where id = :id and (last_event_at is null or last_event_at < :at)", nativeQuery = true)
    int touchLastEvent(@Param("id") UUID id, @Param("at") Instant at);
}
