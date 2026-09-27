package com.opscenter.incident.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * Link between an incident and one of its alerts ({@code incident_alerts}, 03-DB §38.3, D-54).
 * <p>
 * The alert is referenced by id only: the alert table belongs to the alert module, and a JPA
 * relation across modules would couple their entities (D-37). Links are never edited - a later
 * sprint may add manual unlinking as a new command.
 */
@Entity
@Table(name = "incident_alerts")
public class IncidentAlert implements Persistable<IncidentAlertId> {

    @EmbeddedId
    private IncidentAlertId id;

    @Transient
    private boolean isNew = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false, updatable = false, length = 30)
    private AlertRelationType relationType;

    @Column(name = "is_primary", nullable = false, updatable = false)
    private boolean primary;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected IncidentAlert() {
    }

    private IncidentAlert(UUID incidentId, UUID alertId, AlertRelationType relationType, boolean primary,
                          Instant linkedAt) {
        this.id = new IncidentAlertId(incidentId, alertId);
        this.relationType = Objects.requireNonNull(relationType, "relationType");
        this.primary = primary;
        this.linkedAt = Objects.requireNonNull(linkedAt, "linkedAt");
    }

    /** The alert that opened the incident: {@code TRIGGER}, primary. */
    public static IncidentAlert trigger(UUID incidentId, UUID alertId, Instant linkedAt) {
        return new IncidentAlert(incidentId, alertId, AlertRelationType.TRIGGER, true, linkedAt);
    }

    /** A later alert of the same correlation group: {@code CORRELATED}, not primary. */
    public static IncidentAlert correlated(UUID incidentId, UUID alertId, Instant linkedAt) {
        return new IncidentAlert(incidentId, alertId, AlertRelationType.CORRELATED, false, linkedAt);
    }

    @Override
    public IncidentAlertId getId() {
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
        return id.getIncidentId();
    }

    public UUID getAlertId() {
        return id.getAlertId();
    }

    public AlertRelationType getRelationType() {
        return relationType;
    }

    public boolean isPrimary() {
        return primary;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
