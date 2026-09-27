package com.opscenter.alert.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.opscenter.shared.domain.AuditableEntity;
import com.opscenter.shared.domain.Severity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A logical alert = one firing episode of one fingerprint ({@code alerts}, 03-DB §11.1,
 * FR-ALT-01..05, blueprint D-46..D-49).
 * <p>
 * Life of a row:
 * <pre>
 *   fire()  ->  FIRING --observeAgain()--> FIRING (occurrence_count + 1) --resolve()--> RESOLVED
 * </pre>
 * A RESOLVED row is final. If the same fingerprint fires again later, the ingestion creates a new row
 * (outcome REFIRED): one row per episode keeps "how long did it burn" and "how many notifications did
 * dedup absorb" meaningful. The database guarantees at most one FIRING row per fingerprint with the
 * partial unique index {@code uk_alerts_firing_fingerprint} (D-47, D-51).
 * <p>
 * Alerts are written by the system only (the webhook), so {@code created_by}/{@code updated_by}
 * stay {@code null}; {@code service_id} is decided once, when the episode starts (FR-ALT-05).
 * {@code raw_payload} holds a small masked slice of the notification plus the reference to the
 * archived raw body in object storage (D-42) as a JSON string.
 */
@Entity
@Table(name = "alerts")
public class Alert extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "service_id", updatable = false)
    private UUID serviceId;

    @Column(name = "integration_source_id", updatable = false)
    private UUID integrationSourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 50)
    private AlertSourceType sourceType;

    @Column(name = "external_alert_id", length = 255)
    private String externalAlertId;

    @Column(name = "alert_name", nullable = false, updatable = false, length = 255)
    private String alertName;

    @Column(name = "fingerprint", nullable = false, updatable = false, length = 128)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private Severity severity;

    @Column(name = "service_code", updatable = false, length = 100)
    private String serviceCode;

    @Column(name = "environment", updatable = false, length = 50)
    private String environment;

    @Column(name = "instance", updatable = false, length = 255)
    private String instance;

    @Column(name = "summary")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AlertStatus status;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "occurrence_count", nullable = false)
    private long occurrenceCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload")
    private String rawPayload;

    protected Alert() {
    }

    private Alert(UUID id, UUID organizationId, UUID integrationSourceId, AlertSourceType sourceType, UUID serviceId,
                  AlertFacts facts, String rawPayload, Instant now) {
        super(id);
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.integrationSourceId = integrationSourceId;
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.serviceId = serviceId;
        this.alertName = facts.alertName();
        this.fingerprint = facts.fingerprint();
        this.severity = facts.severity();
        this.serviceCode = facts.serviceCode();
        this.environment = facts.environment();
        this.instance = facts.instance();
        this.summary = facts.summary();
        this.externalAlertId = facts.externalAlertId();
        this.rawPayload = rawPayload;
        this.firstSeenAt = now;
        this.lastSeenAt = now;
        this.occurrenceCount = 1;
    }

    /** A new firing episode (outcome CREATED or REFIRED). Times are server time, not the source clock (R-33). */
    public static Alert fire(UUID organizationId, UUID integrationSourceId, AlertSourceType sourceType, UUID serviceId,
                             AlertFacts facts, String rawPayload, Instant now) {
        Alert alert = new Alert(UUID.randomUUID(), organizationId, integrationSourceId, sourceType, serviceId, facts,
                rawPayload, now);
        alert.status = AlertStatus.FIRING;
        return alert;
    }

    /**
     * A "resolved" notification for a fingerprint never seen before - typically OpsCenter was down
     * while it fired (outcome RESOLVED_UNKNOWN, D-59). Recorded for the history, opens no incident.
     */
    public static Alert resolvedWithoutFiring(UUID organizationId, UUID integrationSourceId,
                                              AlertSourceType sourceType, UUID serviceId, AlertFacts facts,
                                              String rawPayload, Instant resolvedAt, Instant now) {
        Alert alert = new Alert(UUID.randomUUID(), organizationId, integrationSourceId, sourceType, serviceId, facts,
                rawPayload, now);
        alert.status = AlertStatus.RESOLVED;
        alert.resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
        return alert;
    }

    /**
     * The same episode was notified again (dedup, TC-DEDUP-001): count it and keep the latest severity,
     * summary and payload slice. No new row, no new incident.
     */
    public void observeAgain(Instant now, Severity latestSeverity, String latestSummary, String latestExternalId,
                             String latestRawPayload) {
        if (status != AlertStatus.FIRING) {
            throw new IllegalStateException("Only a FIRING alert can be observed again; alert " + getId() + " is " + status);
        }
        occurrenceCount++;
        lastSeenAt = now;
        severity = Objects.requireNonNull(latestSeverity, "latestSeverity");
        if (latestSummary != null) {
            summary = latestSummary;
        }
        if (latestExternalId != null) {
            externalAlertId = latestExternalId;
        }
        rawPayload = latestRawPayload;
    }

    /** FIRING -> RESOLVED (D-59). */
    public void resolve(Instant resolvedAt, Instant now, String latestRawPayload) {
        if (status != AlertStatus.FIRING) {
            throw new IllegalStateException("Alert " + getId() + " is already " + status);
        }
        status = AlertStatus.RESOLVED;
        this.resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
        lastSeenAt = now;
        rawPayload = latestRawPayload;
    }

    public boolean isMapped() {
        return serviceId != null;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public UUID getIntegrationSourceId() {
        return integrationSourceId;
    }

    public AlertSourceType getSourceType() {
        return sourceType;
    }

    public String getExternalAlertId() {
        return externalAlertId;
    }

    public String getAlertName() {
        return alertName;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getInstance() {
        return instance;
    }

    public String getSummary() {
        return summary;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public long getOccurrenceCount() {
        return occurrenceCount;
    }

    public String getRawPayload() {
        return rawPayload;
    }
}
