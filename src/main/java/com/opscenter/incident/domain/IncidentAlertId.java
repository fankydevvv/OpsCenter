package com.opscenter.incident.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Composite key of {@code incident_alerts} ({@code PRIMARY KEY (incident_id, alert_id)}, 03-DB §38.3). */
@Embeddable
public class IncidentAlertId implements Serializable {

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "alert_id", nullable = false)
    private UUID alertId;

    protected IncidentAlertId() {
    }

    public IncidentAlertId(UUID incidentId, UUID alertId) {
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.alertId = Objects.requireNonNull(alertId, "alertId");
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public UUID getAlertId() {
        return alertId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IncidentAlertId that)) {
            return false;
        }
        return Objects.equals(incidentId, that.incidentId) && Objects.equals(alertId, that.alertId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(incidentId, alertId);
    }
}
