package com.opscenter.alert.domain;

import java.time.Instant;
import java.util.Objects;
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
 * One notification that touched an alert ({@code alert_occurrences}, 03-DB §11.2) - append-only.
 * <p>
 * {@code source_event_id} is the delivery id of the webhook call, which is also the
 * {@code resource_id} of its idempotency key (D-43). When Alertmanager retries the very same
 * delivery, the webhook finds the key already COMPLETED and rebuilds the original answer from the
 * occurrence rows of that delivery - no second alert, no second incident (TC-ALT-004, TC-IDEMP-001).
 * {@code payload} stores the ingestion decision (outcome, incident link) and the raw archive
 * reference as JSON.
 */
@Entity
@Table(name = "alert_occurrences")
public class AlertOccurrence implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "alert_id", nullable = false, updatable = false)
    private UUID alertId;

    @Column(name = "observed_at", nullable = false, updatable = false)
    private Instant observedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 30)
    private AlertStatus status;

    @Column(name = "source_event_id", updatable = false, length = 255)
    private String sourceEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", updatable = false)
    private String payload;

    protected AlertOccurrence() {
    }

    public AlertOccurrence(UUID alertId, Instant observedAt, AlertStatus status, String sourceEventId,
                           String payloadJson) {
        this.id = UUID.randomUUID();
        this.alertId = Objects.requireNonNull(alertId, "alertId");
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
        this.status = Objects.requireNonNull(status, "status");
        this.sourceEventId = sourceEventId;
        this.payload = payloadJson;
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

    public UUID getAlertId() {
        return alertId;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public String getPayload() {
        return payload;
    }
}
