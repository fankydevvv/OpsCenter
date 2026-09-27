package com.opscenter.incident.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.opscenter.shared.domain.AuditableEntity;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.domain.InvalidRequestException;
import com.opscenter.shared.domain.Severity;

/**
 * The incident aggregate ({@code incidents}, 01-SRS §6-§7, 03-DB §12.1, blueprint D-54..D-61).
 * <p>
 * Every status change goes through one of the command methods below, and each of them asks
 * {@link IncidentStateMachine} first - so an illegal transition is impossible no matter which
 * service calls the entity. The methods return the <em>previous</em> status, which the application
 * service needs for {@code incident_status_history} and the outbox payload.
 * <p>
 * Two columns deserve a note:
 * <ul>
 *   <li>{@code occurrence_count} is mapped {@code updatable = false}. Alert notifications increase
 *       it with a bulk {@code UPDATE ... SET occurrence_count = occurrence_count + 1} that does not
 *       touch {@code version} (D-57): a counter is commutative, and bumping the version for it would
 *       answer 409 to an engineer every time the alert repeats. Because the entity never writes the
 *       column, a later entity update can also never overwrite a counter with a stale value.</li>
 *   <li>{@code version} protects human decisions (acknowledge, resolve ...) and meaningful system
 *       changes (REOPENED, severity raised). A stale version answers
 *       {@code 409 INCIDENT_VERSION_CONFLICT} (04-API §7.2), not the generic concurrency code.</li>
 * </ul>
 */
@Entity
@Table(name = "incidents")
public class Incident extends AuditableEntity {

    public static final int TITLE_MAX = 500;
    public static final int TEXT_MAX = 4000;
    /** Length of {@code incidents.environment} (V010, 03-DB §12.1). */
    public static final int ENVIRONMENT_MAX = 50;
    /** Prefix of an incident whose alert matched no catalog service (D-49). */
    public static final String UNMAPPED_TITLE_PREFIX = "[UNMAPPED] ";

    @Column(name = "incident_no", nullable = false, updatable = false, length = 50)
    private String incidentNo;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "service_id")
    private UUID serviceId;

    @Column(name = "owning_team_id")
    private UUID owningTeamId;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "title", nullable = false, length = TITLE_MAX)
    private String title;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 20)
    private Severity priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private IncidentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 50)
    private IncidentSource source;

    @Column(name = "environment", length = 50)
    private String environment;

    @Column(name = "fingerprint", updatable = false, length = 128)
    private String fingerprint;

    /** Written on INSERT only; incremented by a bulk UPDATE afterwards (see class comment). */
    @Column(name = "occurrence_count", nullable = false, updatable = false)
    private long occurrenceCount;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "investigating_at")
    private Instant investigatingAt;

    @Column(name = "mitigated_at")
    private Instant mitigatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "reopened_at")
    private Instant reopenedAt;

    @Column(name = "root_cause")
    private String rootCause;

    @Column(name = "resolution")
    private String resolution;

    @Column(name = "mitigation_summary")
    private String mitigationSummary;

    @Column(name = "recovery_summary")
    private String recoverySummary;

    protected Incident() {
    }

    private Incident(UUID id) {
        super(id);
    }

    /**
     * Opens a new incident for the first alert of a correlation group (FR-ALT-04, D-54).
     *
     * @param incidentNo     {@code INC-000001} drawn from {@code incident_no_seq} (D-60)
     * @param correlationKey the group's correlation key, stored as {@code fingerprint}
     * @param unmapped       {@code true} = no catalog service; the title gets the {@code [UNMAPPED]} prefix (D-49)
     */
    public static Incident openFromAlert(String incidentNo, UUID organizationId, UUID serviceId, UUID owningTeamId,
                                         String title, String description, Severity severity, IncidentSource source,
                                         String environment, String correlationKey, boolean unmapped) {
        Incident incident = new Incident(UUID.randomUUID());
        incident.incidentNo = Objects.requireNonNull(incidentNo, "incidentNo");
        incident.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        incident.serviceId = serviceId;
        incident.owningTeamId = owningTeamId;
        String base = title == null || title.isBlank() ? "Untitled alert" : title.strip();
        incident.title = truncate(unmapped ? UNMAPPED_TITLE_PREFIX + base : base, TITLE_MAX);
        incident.description = description;
        incident.severity = Objects.requireNonNull(severity, "severity");
        incident.status = IncidentStatus.OPEN;
        incident.source = Objects.requireNonNull(source, "source");
        // Cut to the column like AlertFacts does for the alert row: a label longer than 50 characters
        // must never make the INSERT fail ("value too long") and drop the whole delivery (D-49).
        incident.environment = environment == null ? null : truncate(environment.strip(), ENVIRONMENT_MAX);
        incident.fingerprint = correlationKey;
        incident.occurrenceCount = 1;
        return incident;
    }

    /**
     * Compares the client's {@code version} with the current one (TC-INC-010).
     *
     * @throws ConflictException 409 {@code INCIDENT_VERSION_CONFLICT}
     */
    public void checkVersion(long expectedVersion) {
        if (getVersion() != expectedVersion) {
            throw new ConflictException(IncidentErrorCodes.INCIDENT_VERSION_CONFLICT,
                    "Incident " + incidentNo + " was modified by someone else (expected version " + expectedVersion
                            + ", current " + getVersion() + "). Reload and retry.");
        }
    }

    // --- human commands (02-SAD §9) ----------------------------------------------------------

    /** OPEN|ASSIGNED -> ACKNOWLEDGED; the first acknowledgement time is kept so MTTA stays stable (D-60). */
    public IncidentStatus acknowledge(Instant now) {
        IncidentStatus previous = moveTo(IncidentAction.ACKNOWLEDGE);
        if (acknowledgedAt == null) {
            acknowledgedAt = now;
        }
        return previous;
    }

    /** ACKNOWLEDGED|REOPENED -> INVESTIGATING. */
    public IncidentStatus startInvestigation(Instant now) {
        IncidentStatus previous = moveTo(IncidentAction.START_INVESTIGATION);
        if (investigatingAt == null) {
            investigatingAt = now;
        }
        return previous;
    }

    /** INVESTIGATING -> MITIGATED; the mitigation text is mandatory (04-API §7). */
    public IncidentStatus mitigate(Instant now, String mitigation) {
        String text = requireText("mitigation", mitigation);
        IncidentStatus previous = moveTo(IncidentAction.MITIGATE);
        mitigatedAt = now;
        mitigationSummary = text;
        return previous;
    }

    /**
     * MITIGATED -> RESOLVED; root cause and resolution are mandatory (04-API §7.3, TC-INC-006).
     *
     * @param mitigation optional; replaces the mitigation summary when present
     */
    public IncidentStatus resolve(Instant now, String rootCause, String resolution, String mitigation) {
        String cause = requireText("rootCause", rootCause);
        String fix = requireText("resolution", resolution);
        IncidentStatus previous = moveTo(IncidentAction.RESOLVE);
        resolvedAt = now;
        this.rootCause = cause;
        this.resolution = fix;
        if (mitigation != null && !mitigation.isBlank()) {
            mitigationSummary = truncate(mitigation.strip(), TEXT_MAX);
        }
        return previous;
    }

    /** VERIFIED -> CLOSED (Sprint 4). From RESOLVED the state machine answers 422 RECOVERY_VERIFICATION_REQUIRED. */
    public IncidentStatus close(Instant now) {
        IncidentStatus previous = moveTo(IncidentAction.CLOSE);
        closedAt = now;
        return previous;
    }

    // --- system decisions ----------------------------------------------------------------------

    /**
     * RESOLVED -> REOPENED because an alert of the same group fired again (D-54). {@code resolved_at}
     * is cleared (the incident is unresolved again; the old value stays in the status history),
     * {@code acknowledged_at} is kept (D-60).
     */
    public IncidentStatus reopenBySystem(Instant now) {
        IncidentStatus previous = status;
        status = IncidentStateMachine.reopen(status);
        reopenedAt = now;
        resolvedAt = null;
        return previous;
    }

    /**
     * An incident opened as {@code [UNMAPPED]} adopts the service a later alert of its group resolved
     * to - the operator registered the service in the catalog, exactly what the SERVICE_UNRESOLVED
     * timeline entry asked for (D-48, D-49). The {@code [UNMAPPED]} title prefix is removed, the owning
     * team (for routing, Sprint 3) and a missing environment are taken over. An incident that already
     * has a service never changes it automatically.
     *
     * @return {@code true} when the incident changed
     */
    public boolean adoptService(UUID newServiceId, UUID newOwningTeamId, String newEnvironment) {
        if (serviceId != null || newServiceId == null) {
            return false;
        }
        serviceId = newServiceId;
        owningTeamId = newOwningTeamId;
        if (environment == null && newEnvironment != null) {
            environment = truncate(newEnvironment.strip(), ENVIRONMENT_MAX);
        }
        if (title.startsWith(UNMAPPED_TITLE_PREFIX)) {
            String rest = title.substring(UNMAPPED_TITLE_PREFIX.length()).strip();
            title = rest.isEmpty() ? title : rest;
        }
        return true;
    }

    /**
     * Grouping may only <em>raise</em> the severity (P3 -> P1), never lower it automatically (D-54).
     *
     * @return {@code true} when the severity changed
     */
    public boolean raiseSeverity(Severity candidate) {
        if (candidate != null && candidate.isMoreSevereThan(severity)) {
            severity = candidate;
            return true;
        }
        return false;
    }

    private IncidentStatus moveTo(IncidentAction action) {
        IncidentStatus previous = status;
        status = IncidentStateMachine.next(status, action);
        return previous;
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(ErrorCodes.VALIDATION_FAILED, field + " must not be blank");
        }
        String text = value.strip();
        if (text.length() > TEXT_MAX) {
            throw new InvalidRequestException(ErrorCodes.VALIDATION_FAILED,
                    field + " must be at most " + TEXT_MAX + " characters");
        }
        return text;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    // --- getters -----------------------------------------------------------------------------

    public String getIncidentNo() {
        return incidentNo;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public UUID getOwningTeamId() {
        return owningTeamId;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Severity getPriority() {
        return priority;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public IncidentSource getSource() {
        return source;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public long getOccurrenceCount() {
        return occurrenceCount;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Instant getInvestigatingAt() {
        return investigatingAt;
    }

    public Instant getMitigatedAt() {
        return mitigatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getReopenedAt() {
        return reopenedAt;
    }

    public String getRootCause() {
        return rootCause;
    }

    public String getResolution() {
        return resolution;
    }

    public String getMitigationSummary() {
        return mitigationSummary;
    }

    public String getRecoverySummary() {
        return recoverySummary;
    }
}
