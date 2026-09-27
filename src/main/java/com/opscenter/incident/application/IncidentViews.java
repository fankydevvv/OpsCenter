package com.opscenter.incident.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.opscenter.identity.application.UserLookup;
import com.opscenter.identity.application.UserRef;
import com.opscenter.incident.domain.Incident;
import com.opscenter.incident.domain.IncidentAlert;
import com.opscenter.incident.domain.IncidentStatusHistory;
import com.opscenter.incident.domain.IncidentTimelineEntry;
import com.opscenter.incident.infrastructure.IncidentAlertRepository;
import com.opscenter.incident.infrastructure.IncidentStatusHistoryRepository;
import com.opscenter.organization.application.TeamLookup;
import com.opscenter.organization.application.TeamRef;
import com.opscenter.servicecatalog.application.ServiceLookup;
import com.opscenter.servicecatalog.application.ServiceRef;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns incident entities into API DTOs, always in <b>batches</b>: one query for all services of a
 * page, one for all teams, one for all users, one for the alert counts - never one query per row
 * (the N+1 problem). Services, teams and users belong to other modules and are read only through
 * their lookup ports (D-37); linked alerts through {@link LinkedAlertReader}.
 */
@Component
public class IncidentViews {

    private final ServiceLookup services;
    private final TeamLookup teams;
    private final UserLookup users;
    private final IncidentAlertRepository incidentAlerts;
    private final IncidentStatusHistoryRepository history;
    private final LinkedAlertReader alerts;
    private final JsonMapper jsonMapper;

    public IncidentViews(ServiceLookup services, TeamLookup teams, UserLookup users,
                         IncidentAlertRepository incidentAlerts, IncidentStatusHistoryRepository history,
                         LinkedAlertReader alerts, JsonMapper jsonMapper) {
        this.services = services;
        this.teams = teams;
        this.users = users;
        this.incidentAlerts = incidentAlerts;
        this.history = history;
        this.alerts = alerts;
        this.jsonMapper = jsonMapper;
    }

    public List<IncidentSummary> summaries(List<Incident> incidents) {
        if (incidents.isEmpty()) {
            return List.of();
        }
        References refs = references(incidents, Set.of());
        Map<UUID, Long> alertCounts = alertCounts(incidents.stream().map(Incident::getId).toList());
        return incidents.stream().map(i -> summary(i, refs, alertCounts.getOrDefault(i.getId(), 0L))).toList();
    }

    public IncidentDetail detail(Incident incident, Collection<String> callerPermissions) {
        List<IncidentStatusHistory> changes = history.findByIncidentIdOrderByChangedAtAsc(incident.getId());
        Set<UUID> extraUsers = new HashSet<>();
        changes.stream().map(IncidentStatusHistory::getChangedBy).filter(Objects::nonNull).forEach(extraUsers::add);
        References refs = references(List.of(incident), extraUsers);

        List<IncidentAlert> links = incidentAlerts.findByIdIncidentIdOrderByLinkedAtAsc(incident.getId());
        Map<UUID, LinkedAlertReader.LinkedAlertFacts> facts =
                alerts.findAlerts(links.stream().map(IncidentAlert::getAlertId).toList());
        List<LinkedAlertView> linkedAlerts = links.stream()
                .filter(link -> facts.containsKey(link.getAlertId()))
                .map(link -> {
                    LinkedAlertReader.LinkedAlertFacts alert = facts.get(link.getAlertId());
                    return new LinkedAlertView(alert.id(), alert.alertName(), alert.severity(), alert.status(),
                            alert.instance(), link.getRelationType(), link.isPrimary(), link.getLinkedAt(),
                            alert.occurrenceCount(), alert.lastSeenAt());
                })
                .toList();
        List<StatusHistoryView> statusHistory = changes.stream()
                .map(h -> new StatusHistoryView(h.getFromStatus(), h.getToStatus(), user(refs.users(), h.getChangedBy()),
                        h.getChangedAt(), h.getReason()))
                .toList();

        IncidentSummary s = summary(incident, refs, links.size());
        return new IncidentDetail(s.id(), s.incidentNo(), s.title(), s.severity(), s.priority(), s.status(), s.source(),
                s.service(), s.environment(), s.owningTeam(), s.assignee(), s.occurrenceCount(), s.alertCount(),
                s.createdAt(), s.acknowledgedAt(), s.resolvedAt(), s.updatedAt(), s.version(),
                incident.getDescription(), incident.getFingerprint(), incident.getInvestigatingAt(),
                incident.getMitigatedAt(), incident.getVerifiedAt(), incident.getClosedAt(), incident.getReopenedAt(),
                incident.getRootCause(), incident.getResolution(), incident.getMitigationSummary(),
                incident.getRecoverySummary(), linkedAlerts, statusHistory,
                IncidentPermissions.allowedActions(incident.getStatus(), callerPermissions));
    }

    public List<TimelineEntryView> timeline(List<IncidentTimelineEntry> entries) {
        Set<UUID> actorIds = new HashSet<>();
        entries.stream().map(IncidentTimelineEntry::getActorId).filter(Objects::nonNull).forEach(actorIds::add);
        Map<UUID, UserRef> actors = actorIds.isEmpty() ? Map.of() : users.findUsers(actorIds);
        return entries.stream()
                .map(e -> new TimelineEntryView(e.getId(), e.getEventType(), e.getSource(), user(actors, e.getActorId()),
                        e.getTitle(), e.getDescription(), e.getEventAt(), metadata(e.getMetadata())))
                .toList();
    }

    // --- helpers ----------------------------------------------------------------------------

    private record References(Map<UUID, ServiceRef> services, Map<UUID, TeamRef> teams, Map<UUID, UserRef> users) {
    }

    private References references(List<Incident> incidents, Set<UUID> extraUserIds) {
        Set<UUID> serviceIds = new HashSet<>();
        Set<UUID> teamIds = new HashSet<>();
        Set<UUID> userIds = new HashSet<>(extraUserIds);
        for (Incident incident : incidents) {
            addIfPresent(serviceIds, incident.getServiceId());
            addIfPresent(teamIds, incident.getOwningTeamId());
            addIfPresent(userIds, incident.getAssigneeId());
        }
        return new References(
                serviceIds.isEmpty() ? Map.of() : services.findRefs(serviceIds),
                teamIds.isEmpty() ? Map.of() : teams.findTeams(teamIds),
                userIds.isEmpty() ? Map.of() : users.findUsers(userIds));
    }

    private Map<UUID, Long> alertCounts(List<UUID> incidentIds) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : incidentAlerts.countByIncidentIds(incidentIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private static IncidentSummary summary(Incident incident, References refs, long alertCount) {
        ServiceRef service = incident.getServiceId() == null ? null : refs.services().get(incident.getServiceId());
        TeamRef team = incident.getOwningTeamId() == null ? null : refs.teams().get(incident.getOwningTeamId());
        return new IncidentSummary(incident.getId(), incident.getIncidentNo(), incident.getTitle(),
                incident.getSeverity(), incident.getPriority(), incident.getStatus(), incident.getSource(),
                service == null ? null : new ServiceBrief(service.id(), service.code(), service.name()),
                incident.getEnvironment(),
                team == null ? null : new TeamBrief(team.id(), team.code(), team.name()),
                user(refs.users(), incident.getAssigneeId()),
                incident.getOccurrenceCount(), alertCount, incident.getCreatedAt(), incident.getAcknowledgedAt(),
                incident.getResolvedAt(), incident.getUpdatedAt(), incident.getVersion());
    }

    private static UserBrief user(Map<UUID, UserRef> users, UUID id) {
        if (id == null) {
            return null;
        }
        UserRef ref = users.get(id);
        return ref == null ? new UserBrief(id, null, null) : new UserBrief(ref.id(), ref.username(), ref.displayName());
    }

    private JsonNode metadata(String json) {
        return json == null ? null : jsonMapper.readTree(json);
    }

    private static void addIfPresent(Set<UUID> target, UUID id) {
        if (id != null) {
            target.add(id);
        }
    }
}
