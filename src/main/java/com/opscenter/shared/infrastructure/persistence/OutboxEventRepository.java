package com.opscenter.shared.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@code outbox_events}.
 * <p>
 * {@link #lockNextBatch} uses {@code SELECT ... FOR UPDATE SKIP LOCKED} (pessimistic lock with
 * lock timeout {@code -2} = skip locked on PostgreSQL) so two relay instances never publish the
 * same event twice; the single-instance base does not need it yet, but the query is the standard
 * outbox pattern and costs nothing. Only rows whose backoff has elapsed
 * ({@code next_attempt_at <= now}) are returned (D-28).
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from OutboxEventEntity e where e.status = :status and e.nextAttemptAt <= :now "
            + "order by e.occurredAt asc")
    List<OutboxEventEntity> lockNextBatch(@Param("status") OutboxEventStatus status, @Param("now") Instant now,
                                          Limit limit);
}
