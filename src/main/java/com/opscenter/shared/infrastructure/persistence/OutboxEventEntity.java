package com.opscenter.shared.infrastructure.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * JPA mapping of {@code outbox_events} (03-DB §23, plus {@code next_attempt_at} from V006 / D-28).
 * <p>
 * The payload is kept as a JSON {@link String} mapped to {@code jsonb} with
 * {@code @JdbcTypeCode(SqlTypes.JSON)} (D-12): serialisation happens explicitly in
 * {@code OutboxAppender}, so persistence does not depend on Hibernate's Jackson integration.
 * {@code next_attempt_at} implements exponential backoff: a broker outage must not burn through
 * {@code max-retries} in seconds and park every event as {@code FAILED} (TC-OUTBOX-002).
 * {@link Persistable}: the id is assigned by the application, so this hint spares Spring Data a
 * SELECT before every insert (see {@code AuditableEntity}).
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OutboxEventStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error")
    private String lastError;

    /** Earliest moment the relay may try (again); equals {@code occurred_at} for a fresh event. */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    protected OutboxEventEntity() {
    }

    public OutboxEventEntity(UUID id, String aggregateType, UUID aggregateId, String eventType,
                             String payload, Instant occurredAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.nextAttemptAt = occurredAt;
        this.status = OutboxEventStatus.PENDING;
        this.retryCount = 0;
    }

    public void markPublished(Instant publishedAt) {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.lastError = null;
    }

    /**
     * Records a failed publish attempt and schedules the next one with exponential backoff
     * ({@code initialBackoff * 2^(retries-1)}, capped at {@code maxBackoff}). The row stays
     * {@code PENDING} until {@code maxRetries} is reached, after which it becomes {@code FAILED}
     * and needs operator attention (D-14, D-28).
     */
    public void markAttemptFailed(String error, int maxRetries, Instant now, Duration initialBackoff,
                                  Duration maxBackoff) {
        this.retryCount++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
        this.nextAttemptAt = now.plus(backoffFor(retryCount, initialBackoff, maxBackoff));
        if (this.retryCount >= maxRetries) {
            this.status = OutboxEventStatus.FAILED;
        }
    }

    /** Backoff for the given (1-based) attempt number; overflow-safe because of the cap. */
    static Duration backoffFor(int attempt, Duration initialBackoff, Duration maxBackoff) {
        Duration backoff = initialBackoff;
        for (int i = 1; i < attempt && backoff.compareTo(maxBackoff) < 0; i++) {
            backoff = backoff.multipliedBy(2);
        }
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }

    /**
     * RabbitMQ routing key derived from the event type (04-API §15):
     * {@code UserLocked -> user.locked}, {@code IncidentAcknowledged -> incident.acknowledged}.
     */
    public String routingKey() {
        return eventType.replaceAll("([a-z0-9])([A-Z])", "$1.$2").toLowerCase(Locale.ROOT);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public OutboxEventStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }
}
