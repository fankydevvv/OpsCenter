package com.opscenter.incident.domain;

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

import org.springframework.data.domain.Persistable;

/**
 * One status change of an incident ({@code incident_status_history}, 03-DB §13.2).
 * <p>
 * Append-only by construction (03-DB §29): every column is {@code updatable = false}, there are
 * no setters and no API to edit or delete a row. It answers "who moved the incident from what to
 * what, when and why" - {@code changedBy = null} marks a system transition.
 */
@Entity
@Table(name = "incident_status_history")
public class IncidentStatusHistory implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 30)
    private IncidentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 30)
    private IncidentStatus toStatus;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(name = "reason", updatable = false)
    private String reason;

    protected IncidentStatusHistory() {
    }

    /**
     * @param fromStatus {@code null} for the creation of the incident
     * @param changedBy  {@code null} for a system transition
     */
    public IncidentStatusHistory(UUID incidentId, IncidentStatus fromStatus, IncidentStatus toStatus,
                                 UUID changedBy, Instant changedAt, String reason) {
        this.id = UUID.randomUUID();
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.fromStatus = fromStatus;
        this.toStatus = Objects.requireNonNull(toStatus, "toStatus");
        this.changedBy = changedBy;
        this.changedAt = Objects.requireNonNull(changedAt, "changedAt");
        this.reason = reason;
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

    public UUID getIncidentId() {
        return incidentId;
    }

    public IncidentStatus getFromStatus() {
        return fromStatus;
    }

    public IncidentStatus getToStatus() {
        return toStatus;
    }

    public UUID getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getReason() {
        return reason;
    }
}
