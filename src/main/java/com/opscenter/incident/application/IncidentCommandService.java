package com.opscenter.incident.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.incident.domain.Incident;
import com.opscenter.incident.domain.IncidentAction;
import com.opscenter.incident.domain.IncidentAuditActions;
import com.opscenter.incident.domain.IncidentErrorCodes;
import com.opscenter.incident.domain.IncidentEvents;
import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.incident.domain.TimelineEventType;
import com.opscenter.incident.domain.TimelineSource;
import com.opscenter.incident.infrastructure.IncidentRepository;
import com.opscenter.shared.application.CurrentUser;
import com.opscenter.shared.application.OutboxAppender;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.NotFoundException;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Human commands on an incident (04-API §7, §7.2, §7.3; blueprint §4.3, §8.6; D-55..D-57).
 * <p>
 * Every command is the same recipe in <b>one transaction</b> (03-DB §27, §38.5):
 * <ol>
 *   <li>load the incident (404 {@code INCIDENT_NOT_FOUND});</li>
 *   <li>compare the client's {@code version} (409 {@code INCIDENT_VERSION_CONFLICT}, TC-INC-010);</li>
 *   <li>let the aggregate apply the command - the state machine rejects illegal transitions
 *       (409 {@code INCIDENT_INVALID_TRANSITION}, 422 {@code RECOVERY_VERIFICATION_REQUIRED});</li>
 *   <li>{@code saveAndFlush}: if another request changed the row between steps 2 and 4, Hibernate's
 *       optimistic lock fails here and is reported with the same 409 code;</li>
 *   <li>status history, timeline {@code STATUS_CHANGED}, audit (actor, before/after) and the outbox
 *       event.</li>
 * </ol>
 * Either all of it commits or nothing does - there is never a status without its history line or
 * its event. Notifications are not sent here: consumers of the outbox do that (Sprint 3).
 */
@Service
public class IncidentCommandService {

    private static final String INCIDENT_RESOURCE = "Incident";

    private final IncidentRepository incidents;
    private final IncidentViews views;
    private final IncidentTimelineRecorder journal;
    private final AuditRecorder audit;
    private final OutboxAppender outbox;
    private final CurrentUser currentUser;
    private final Clock clock;

    public IncidentCommandService(IncidentRepository incidents, IncidentViews views, IncidentTimelineRecorder journal,
                                  AuditRecorder audit, OutboxAppender outbox, CurrentUser currentUser, Clock clock) {
        this.incidents = incidents;
        this.views = views;
        this.journal = journal;
        this.audit = audit;
        this.outbox = outbox;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** {@code POST /incidents/{id}/acknowledge}: OPEN|ASSIGNED -> ACKNOWLEDGED (TC-INC-003). */
    @Transactional
    public IncidentDetail acknowledge(UUID id, long version, String note, Collection<String> callerPermissions) {
        return apply(id, version, IncidentAction.ACKNOWLEDGE, note, callerPermissions,
                (incident, now) -> incident.acknowledge(now));
    }

    /** {@code POST /incidents/{id}/start-investigation}: ACKNOWLEDGED|REOPENED -> INVESTIGATING (TC-INC-004). */
    @Transactional
    public IncidentDetail startInvestigation(UUID id, long version, String note, Collection<String> callerPermissions) {
        return apply(id, version, IncidentAction.START_INVESTIGATION, note, callerPermissions,
                (incident, now) -> incident.startInvestigation(now));
    }

    /** {@code POST /incidents/{id}/mitigate}: INVESTIGATING -> MITIGATED (TC-INC-005). */
    @Transactional
    public IncidentDetail mitigate(UUID id, long version, String mitigation, Collection<String> callerPermissions) {
        return apply(id, version, IncidentAction.MITIGATE, mitigation, callerPermissions,
                (incident, now) -> incident.mitigate(now, mitigation));
    }

    /** {@code POST /incidents/{id}/resolve}: MITIGATED -> RESOLVED with root cause + resolution (TC-INC-006). */
    @Transactional
    public IncidentDetail resolve(UUID id, long version, String rootCause, String resolution, String mitigation,
                                  Collection<String> callerPermissions) {
        return apply(id, version, IncidentAction.RESOLVE, resolution, callerPermissions,
                (incident, now) -> incident.resolve(now, rootCause, resolution, mitigation));
    }

    /**
     * {@code POST /incidents/{id}/close}. In Sprint 2 this never succeeds: from RESOLVED the state
     * machine answers 422 {@code RECOVERY_VERIFICATION_REQUIRED} (TC-INC-007), from anything else
     * 409 (TC-VER-004). It becomes VERIFIED -> CLOSED once verification exists (Sprint 4).
     */
    @Transactional
    public IncidentDetail close(UUID id, long version, String note, Collection<String> callerPermissions) {
        return apply(id, version, IncidentAction.CLOSE, note, callerPermissions,
                (incident, now) -> incident.close(now));
    }

    /** One transition = the numbered recipe of the class comment. */
    private IncidentDetail apply(UUID id, long version, IncidentAction action, String note,
                                 Collection<String> callerPermissions,
                                 BiFunction<Incident, Instant, IncidentStatus> command) {
        Incident incident = incidents.findById(id)
                .orElseThrow(() -> new NotFoundException(IncidentErrorCodes.INCIDENT_NOT_FOUND,
                        "Incident " + id + " not found"));
        incident.checkVersion(version);
        IncidentSnapshot before = IncidentSnapshot.of(incident);
        Instant now = IncidentTimelineRecorder.start(clock.instant());

        IncidentStatus from = command.apply(incident, now);
        try {
            incidents.saveAndFlush(incident);
        }
        catch (OptimisticLockingFailureException raceLost) {
            // Someone committed a change after we read the row: same answer as a stale version.
            throw new ConflictException(IncidentErrorCodes.INCIDENT_VERSION_CONFLICT,
                    "Incident " + incident.getIncidentNo() + " was modified concurrently. Reload and retry.");
        }
        IncidentStatus to = incident.getStatus();
        UUID actor = currentUser.actorId().orElse(null);
        String reason = note == null || note.isBlank() ? null : note.strip();

        journal.statusChange(incident.getId(), from, to, actor, now, reason);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", action.name());
        metadata.put("fromStatus", from.name());
        metadata.put("toStatus", to.name());
        journal.timeline(incident.getId(), TimelineEventType.STATUS_CHANGED, TimelineSource.USER, actor,
                from + " → " + to, reason, now, metadata);
        audit.record(auditAction(action), INCIDENT_RESOURCE, incident.getId(), before, IncidentSnapshot.of(incident),
                actor, incident.getOrganizationId());
        outbox.append(IncidentEvents.AGGREGATE_TYPE, incident.getId(), eventType(action),
                new IncidentEventPayloads.StatusChanged(incident.getId(), incident.getIncidentNo(), from, to, actor, now,
                        incident.getVersion(), null,
                        action == IncidentAction.RESOLVE ? incident.getRootCause() != null : null));
        return views.detail(incident, callerPermissions);
    }

    private static String auditAction(IncidentAction action) {
        return switch (action) {
            case ACKNOWLEDGE -> IncidentAuditActions.INCIDENT_ACKNOWLEDGED;
            case START_INVESTIGATION -> IncidentAuditActions.INCIDENT_INVESTIGATION_STARTED;
            case MITIGATE -> IncidentAuditActions.INCIDENT_MITIGATED;
            case RESOLVE -> IncidentAuditActions.INCIDENT_RESOLVED;
            case CLOSE -> IncidentAuditActions.INCIDENT_CLOSED;
        };
    }

    private static String eventType(IncidentAction action) {
        return switch (action) {
            case ACKNOWLEDGE -> IncidentEvents.INCIDENT_ACKNOWLEDGED;
            case START_INVESTIGATION -> IncidentEvents.INCIDENT_INVESTIGATION_STARTED;
            case MITIGATE -> IncidentEvents.INCIDENT_MITIGATED;
            case RESOLVE -> IncidentEvents.INCIDENT_RESOLVED;
            case CLOSE -> IncidentEvents.INCIDENT_CLOSED;
        };
    }
}
