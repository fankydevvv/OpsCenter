package com.opscenter.incident.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.incident.domain.Incident;
import com.opscenter.incident.domain.IncidentAlert;
import com.opscenter.incident.domain.IncidentAuditActions;
import com.opscenter.incident.domain.IncidentEvents;
import com.opscenter.incident.domain.IncidentSource;
import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.incident.domain.TimelineEventType;
import com.opscenter.incident.domain.TimelineSource;
import com.opscenter.incident.infrastructure.IncidentAlertRepository;
import com.opscenter.incident.infrastructure.IncidentRepository;
import com.opscenter.shared.application.OutboxAppender;
import com.opscenter.shared.domain.Severity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Groups alerts into incidents (FR-ALT-04, blueprint §8.5, D-49, D-54, D-59) - the incident module's
 * entry point for the alert module.
 * <p>
 * Called <b>inside</b> the alert ingestion transaction ({@code Propagation.MANDATORY}) while the
 * caller holds the distributed lock of the alert's correlation group (D-50). That is what makes
 * "read the open incident, then create one if there is none" safe: two deliveries of the same
 * group are serialised by the lock, and if the lock ever failed (Redis TTL expired during a long
 * pause, R-23) the partial unique index {@code uk_incidents_open_correlation} rejects the second
 * incident and the caller retries the whole delivery once (D-51).
 * <p>
 * Decision for a new logical alert (CREATED or REFIRED):
 * <ol>
 *   <li>an <b>open</b> incident with the same correlation key exists -> link the alert
 *       ({@code CORRELATED}), count the occurrence, raise the severity if the alert is more severe;</li>
 *   <li>else a <b>RESOLVED</b> incident of the group was resolved within
 *       {@code opscenter.alert.reopen-window} (default 24 h, {@code PT0S} disables it) -> the problem
 *       came back: system transition RESOLVED -> REOPENED (01-SRS §7) and link;</li>
 *   <li>else open a new incident {@code OPEN} with the alert as {@code TRIGGER}. An alert without a
 *       catalog service still opens one, titled {@code [UNMAPPED] ...} - no alert is ever dropped
 *       (D-49, TC-ROUTE-003).</li>
 * </ol>
 * Every decision writes timeline + (for status changes) history + audit + outbox in the caller's
 * transaction.
 */
@Service
public class IncidentCorrelationService {

    private static final String INCIDENT_RESOURCE = "Incident";

    private final IncidentRepository incidents;
    private final IncidentAlertRepository incidentAlerts;
    private final IncidentTimelineRecorder journal;
    private final LinkedAlertReader alerts;
    private final AuditRecorder audit;
    private final OutboxAppender outbox;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Duration reopenWindow;

    public IncidentCorrelationService(IncidentRepository incidents, IncidentAlertRepository incidentAlerts,
                                      IncidentTimelineRecorder journal, LinkedAlertReader alerts, AuditRecorder audit,
                                      OutboxAppender outbox, MeterRegistry meters, Clock clock,
                                      @Value("${opscenter.alert.reopen-window:PT24H}") Duration reopenWindow) {
        this.incidents = incidents;
        this.incidentAlerts = incidentAlerts;
        this.journal = journal;
        this.alerts = alerts;
        this.audit = audit;
        this.outbox = outbox;
        this.meters = meters;
        this.clock = clock;
        this.reopenWindow = reopenWindow == null ? Duration.ZERO : reopenWindow;
    }

    /** Groups a NEW logical alert (outcome CREATED or REFIRED) - see the class comment. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CorrelationResult correlate(AlertCorrelationRequest request) {
        Instant now = IncidentTimelineRecorder.start(clock.instant());
        Optional<Incident> open = incidents.findFirstByOrganizationIdAndFingerprintAndStatusIn(
                request.organizationId(), request.correlationKey(), IncidentStatus.openStates());
        if (open.isPresent()) {
            return linkToOpenIncident(open.get(), request, now);
        }
        if (!reopenWindow.isZero() && !reopenWindow.isNegative()) {
            Optional<Incident> recentlyResolved = incidents
                    .findFirstByOrganizationIdAndFingerprintAndStatusAndResolvedAtGreaterThanEqualOrderByResolvedAtDesc(
                            request.organizationId(), request.correlationKey(), IncidentStatus.RESOLVED,
                            now.minus(reopenWindow));
            if (recentlyResolved.isPresent()) {
                return reopen(recentlyResolved.get(), request, now);
            }
        }
        return openIncident(request, now);
    }

    /**
     * A repeated notification of an alert that is still FIRING (DEDUPLICATED, TC-DEDUP-001): the
     * occurrence counters of its incidents move by bulk update, no version bump (D-57). If the
     * notification carries a higher severity than an <em>open</em> incident of the alert (the
     * critical rule of a warning/critical pair started firing), the incident's severity is raised -
     * the same "only ever upwards" rule as for new alerts (D-54), with timeline, audit, outbox and a
     * version bump because it is a meaningful change a responder must see.
     *
     * @param severity the alert's severity after this notification
     * @return the incident the alert belongs to, for the webhook summary
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<IncidentRef> recordRepeatedOccurrence(UUID alertId, String alertName, Severity severity) {
        List<UUID> incidentIds = incidentAlerts.findIncidentIdsByAlertId(alertId);
        if (incidentIds.isEmpty()) {
            return Optional.empty();
        }
        Instant now = IncidentTimelineRecorder.start(clock.instant());
        List<Incident> linked = new ArrayList<>(incidents.findAllById(incidentIds));
        for (Incident incident : linked) {
            Severity before = incident.getSeverity();
            if (incident.getStatus().isOpen() && incident.raiseSeverity(severity)) {
                // Entity update first: the bulk counter below flushes it and never touches the version.
                incidents.saveAndFlush(incident);
                recordSeverityRaised(incident, before, alertName, alertId, now);
            }
        }
        incidents.incrementOccurrences(incidentIds, 1, now);
        return latest(linked);
    }

    /**
     * The source says an alert is resolved (D-59): every incident of the alert gets a timeline entry;
     * when <em>all</em> alerts of an incident are resolved it also gets "All alerts resolved" and an
     * {@code IncidentUpdated} event. The incident status is NOT changed - resolving needs a root
     * cause written by a person.
     *
     * @return the incidents that were told (the alert normally belongs to exactly one)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<IncidentRef> recordAlertResolved(ResolvedAlertNotice notice) {
        List<IncidentRef> told = new ArrayList<>();
        Instant at = IncidentTimelineRecorder.start(clock.instant());
        for (Incident incident : incidents.findAllById(incidentAlerts.findIncidentIdsByAlertId(notice.alertId()))) {
            UUID incidentId = incident.getId();
            journal.timeline(incidentId, TimelineEventType.ALERT_RESOLVED, timelineSource(notice.source()), null,
                    "Alert " + notice.alertName() + " resolved at source",
                    describeInstance(notice.instance()) + "resolved at " + notice.resolvedAt(), at,
                    Map.of("alertId", notice.alertId()));
            // The caller flushed the RESOLVED alert, so the reader sees its new status in this transaction.
            Map<UUID, LinkedAlertReader.LinkedAlertFacts> facts =
                    alerts.findAlerts(incidentAlerts.findAlertIdsByIncidentId(incidentId));
            boolean allResolved = !facts.isEmpty()
                    && facts.values().stream().allMatch(LinkedAlertReader.LinkedAlertFacts::resolved);
            if (allResolved) {
                journal.timeline(incidentId, TimelineEventType.ALL_ALERTS_RESOLVED, TimelineSource.SYSTEM, null,
                        "All alerts resolved at source",
                        "Every alert of this incident has recovered. Verify the service and resolve the incident "
                                + "with its root cause.", IncidentTimelineRecorder.next(at),
                        Map.of("alertCount", facts.size()));
                outbox.append(IncidentEvents.AGGREGATE_TYPE, incidentId, IncidentEvents.INCIDENT_UPDATED,
                        new IncidentEventPayloads.Updated(incidentId, incident.getIncidentNo(),
                                IncidentEvents.CHANGE_ALL_ALERTS_RESOLVED, incident.getSeverity(), incident.getVersion()));
            }
            told.add(new IncidentRef(incidentId, incident.getIncidentNo(), incident.getStatus()));
        }
        return told;
    }

    // --- the three correlation outcomes -------------------------------------------------------

    private CorrelationResult linkToOpenIncident(Incident incident, AlertCorrelationRequest request, Instant now) {
        incidentAlerts.save(IncidentAlert.correlated(incident.getId(), request.alertId(), now));
        Instant at = now;
        if (request.mapped() && incident.getServiceId() == null) {
            at = adoptService(incident, request, at);
        }
        Severity before = incident.getSeverity();
        if (incident.raiseSeverity(request.severity())) {
            incidents.saveAndFlush(incident);
            at = recordSeverityRaised(incident, before, request.alertName(), request.alertId(), at);
        }
        journal.timeline(incident.getId(), TimelineEventType.ALERT_LINKED, timelineSource(request.source()), null,
                "Alert " + request.alertName() + " linked", describeInstance(request.instance()) + "same group "
                        + "(alert name, service, environment) as this open incident", IncidentTimelineRecorder.next(at),
                Map.of("alertId", request.alertId(), "relationType", "CORRELATED"));
        incidents.incrementOccurrences(List.of(incident.getId()), 1, now);
        outbox.append(IncidentEvents.AGGREGATE_TYPE, incident.getId(), IncidentEvents.INCIDENT_UPDATED,
                new IncidentEventPayloads.Updated(incident.getId(), incident.getIncidentNo(),
                        IncidentEvents.CHANGE_ALERT_LINKED, incident.getSeverity(), incident.getVersion()));
        return new CorrelationResult(incident.getId(), incident.getIncidentNo(), CorrelationResult.Action.LINKED);
    }

    private CorrelationResult reopen(Incident incident, AlertCorrelationRequest request, Instant now) {
        IncidentSnapshot before = IncidentSnapshot.of(incident);
        Severity severityBefore = incident.getSeverity();
        IncidentStatus from = incident.reopenBySystem(now);
        boolean raised = incident.raiseSeverity(request.severity());
        incidents.saveAndFlush(incident);
        incidentAlerts.save(IncidentAlert.correlated(incident.getId(), request.alertId(), now));

        String reason = "Alert " + request.alertName() + " fired again within the reopen window (" + reopenWindow + ")";
        journal.statusChange(incident.getId(), from, incident.getStatus(), null, now, reason);
        Instant at = now;
        journal.timeline(incident.getId(), TimelineEventType.INCIDENT_REOPENED, TimelineSource.SYSTEM, null,
                from + " → " + incident.getStatus(), reason, at,
                Map.of("alertId", request.alertId(), "fromStatus", from.name(), "toStatus", incident.getStatus().name()));
        if (raised) {
            at = recordSeverityRaised(incident, severityBefore, request.alertName(), request.alertId(), at);
        }
        incidents.incrementOccurrences(List.of(incident.getId()), 1, now);
        audit.record(IncidentAuditActions.INCIDENT_REOPENED, INCIDENT_RESOURCE, incident.getId(), before,
                IncidentSnapshot.of(incident), null, incident.getOrganizationId());
        outbox.append(IncidentEvents.AGGREGATE_TYPE, incident.getId(), IncidentEvents.INCIDENT_REOPENED,
                new IncidentEventPayloads.StatusChanged(incident.getId(), incident.getIncidentNo(), from,
                        incident.getStatus(), null, now, incident.getVersion(), reason, null));
        return new CorrelationResult(incident.getId(), incident.getIncidentNo(), CorrelationResult.Action.REOPENED);
    }

    private CorrelationResult openIncident(AlertCorrelationRequest request, Instant now) {
        String incidentNo = String.format("INC-%06d", incidents.nextIncidentNumber());
        Incident incident = Incident.openFromAlert(incidentNo, request.organizationId(), request.serviceId(),
                request.owningTeamId(), request.title(), request.description(), request.severity(), request.source(),
                request.environment(), request.correlationKey(), !request.mapped());
        // Flush now: a concurrent incident of the same group (lock lost) fails HERE on
        // uk_incidents_open_correlation, and the ingestion retries the delivery once (D-51).
        incidents.saveAndFlush(incident);
        incidentAlerts.save(IncidentAlert.trigger(incident.getId(), request.alertId(), now));

        journal.statusChange(incident.getId(), null, IncidentStatus.OPEN, null, now,
                "Opened from alert " + request.alertName());
        Instant at = now;
        journal.timeline(incident.getId(), TimelineEventType.INCIDENT_CREATED, timelineSource(request.source()), null,
                "Incident opened from alert " + request.alertName(), request.title(), at,
                creationMetadata(request));
        if (!request.mapped()) {
            at = IncidentTimelineRecorder.next(at);
            journal.timeline(incident.getId(), TimelineEventType.SERVICE_UNRESOLVED, TimelineSource.SYSTEM, null,
                    "Service not found in the catalog",
                    request.serviceCode() == null
                            ? "The alert carries no service label (service, service_name or app)."
                            : "No active service with code '" + request.serviceCode() + "' is registered. Register it "
                                    + "in the Service Catalog so future alerts are routed to its team.",
                    at, request.serviceCode() == null ? Map.of() : Map.of("serviceCode", request.serviceCode()));
        }
        else if (request.environment() != null && !request.environmentRegistered()) {
            at = IncidentTimelineRecorder.next(at);
            journal.timeline(incident.getId(), TimelineEventType.ENVIRONMENT_NOT_REGISTERED, TimelineSource.SYSTEM,
                    null, "Environment " + request.environment() + " is not registered for the service",
                    "The alert was still attached to service '" + request.serviceCode() + "'.", at,
                    Map.of("environment", request.environment()));
        }
        if (request.severityDefaulted()) {
            at = IncidentTimelineRecorder.next(at);
            journal.timeline(incident.getId(), TimelineEventType.SEVERITY_DEFAULTED, TimelineSource.SYSTEM, null,
                    "Severity defaulted to " + request.severity(),
                    request.rawSeverity() == null ? "The alert has no severity label."
                            : "Unknown severity label '" + request.rawSeverity() + "'.", at, Map.of());
        }

        audit.record(IncidentAuditActions.INCIDENT_CREATED, INCIDENT_RESOURCE, incident.getId(), null,
                IncidentSnapshot.of(incident), null, incident.getOrganizationId());
        outbox.append(IncidentEvents.AGGREGATE_TYPE, incident.getId(), IncidentEvents.INCIDENT_CREATED,
                new IncidentEventPayloads.Created(incident.getId(), incident.getIncidentNo(), incident.getTitle(),
                        incident.getSeverity(), incident.getStatus(), incident.getSource(), incident.getServiceId(),
                        incident.getEnvironment(), incident.getOwningTeamId(), request.alertId(), now));
        Counter.builder("opscenter.incidents.created")
                .description("Incidents opened from alerts")
                .tag("severity", incident.getSeverity().name())
                .register(meters)
                .increment();
        return new CorrelationResult(incident.getId(), incident.getIncidentNo(), CorrelationResult.Action.CREATED);
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * The group's first alert was UNMAPPED; this one resolved to a catalog service (registered in the
     * meantime). The incident takes the service and its team over so it shows up - and later routes -
     * where it belongs (D-48, D-49). Version bump + timeline + audit + outbox: a responder must see it.
     */
    private Instant adoptService(Incident incident, AlertCorrelationRequest request, Instant previous) {
        IncidentSnapshot before = IncidentSnapshot.of(incident);
        if (!incident.adoptService(request.serviceId(), request.owningTeamId(), request.environment())) {
            return previous;
        }
        incidents.saveAndFlush(incident);
        Instant at = IncidentTimelineRecorder.next(previous);
        journal.timeline(incident.getId(), TimelineEventType.SERVICE_RESOLVED, TimelineSource.SYSTEM, null,
                "Service " + request.serviceCode() + " resolved from the catalog",
                "Alert " + request.alertName() + " matched the now registered service '" + request.serviceCode()
                        + "'; the incident is attached to it and its owning team.", at,
                Map.of("serviceId", request.serviceId(), "alertId", request.alertId()));
        audit.record(IncidentAuditActions.INCIDENT_SERVICE_RESOLVED, INCIDENT_RESOURCE, incident.getId(), before,
                IncidentSnapshot.of(incident), null, incident.getOrganizationId());
        outbox.append(IncidentEvents.AGGREGATE_TYPE, incident.getId(), IncidentEvents.INCIDENT_UPDATED,
                new IncidentEventPayloads.Updated(incident.getId(), incident.getIncidentNo(),
                        IncidentEvents.CHANGE_SERVICE_RESOLVED, incident.getSeverity(), incident.getVersion()));
        return at;
    }

    private Instant recordSeverityRaised(Incident incident, Severity before, String alertName, UUID alertId,
                                         Instant previous) {
        Instant at = IncidentTimelineRecorder.next(previous);
        journal.timeline(incident.getId(), TimelineEventType.SEVERITY_RAISED, TimelineSource.SYSTEM, null,
                "Severity raised " + before + " → " + incident.getSeverity(),
                "Alert " + alertName + " is more severe than the incident.", at,
                Map.of("from", before.name(), "to", incident.getSeverity().name(), "alertId", alertId));
        audit.record(IncidentAuditActions.INCIDENT_SEVERITY_CHANGED, INCIDENT_RESOURCE, incident.getId(),
                Map.of("severity", before.name()), Map.of("severity", incident.getSeverity().name()), null,
                incident.getOrganizationId());
        outbox.append(IncidentEvents.AGGREGATE_TYPE, incident.getId(), IncidentEvents.INCIDENT_UPDATED,
                new IncidentEventPayloads.Updated(incident.getId(), incident.getIncidentNo(),
                        IncidentEvents.CHANGE_SEVERITY_RAISED, incident.getSeverity(), incident.getVersion()));
        return at;
    }

    private static Optional<IncidentRef> latest(List<Incident> linked) {
        return linked.stream()
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .map(i -> new IncidentRef(i.getId(), i.getIncidentNo(), i.getStatus()));
    }

    private static Map<String, Object> creationMetadata(AlertCorrelationRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("alertId", request.alertId());
        metadata.put("severity", request.severity().name());
        metadata.put("mappingStatus", request.mapped() ? "MAPPED" : "UNMAPPED");
        if (request.serviceCode() != null) {
            metadata.put("serviceCode", request.serviceCode());
        }
        if (request.environment() != null) {
            metadata.put("environment", request.environment());
        }
        return metadata;
    }

    private static String describeInstance(String instance) {
        return instance == null || instance.isBlank() ? "" : "Instance " + instance + "; ";
    }

    /** Events reported by a source are attributed to it; USER only for human commands. */
    static TimelineSource timelineSource(IncidentSource source) {
        return switch (source) {
            case ALERTMANAGER -> TimelineSource.ALERTMANAGER;
            case WEBHOOK -> TimelineSource.WEBHOOK;
            case API -> TimelineSource.API;
            case MANUAL -> TimelineSource.USER;
        };
    }
}
