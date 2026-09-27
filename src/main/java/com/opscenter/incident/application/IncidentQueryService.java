package com.opscenter.incident.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.opscenter.incident.domain.Incident;
import com.opscenter.incident.domain.IncidentErrorCodes;
import com.opscenter.incident.domain.IncidentTimelineEntry;
import com.opscenter.incident.infrastructure.IncidentRepository;
import com.opscenter.incident.infrastructure.IncidentSpecifications;
import com.opscenter.incident.infrastructure.IncidentTimelineRepository;
import com.opscenter.servicecatalog.application.ServiceLookup;
import com.opscenter.shared.application.Sorting;
import com.opscenter.shared.domain.NotFoundException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read use cases of incidents ({@code GET /api/v1/incidents*}, 04-API §7, blueprint §7.4).
 * Sorting is whitelisted (04-API §2.4); the default is "newest first".
 */
@Service
public class IncidentQueryService {

    /** Properties a client may sort by. */
    static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "severity", "status", "incidentNo");

    private final IncidentRepository incidents;
    private final IncidentTimelineRepository timeline;
    private final IncidentViews views;

    public IncidentQueryService(IncidentRepository incidents, IncidentTimelineRepository timeline,
                                IncidentViews views) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public Page<IncidentSummary> list(IncidentListQuery query, Pageable pageable) {
        List<Specification<Incident>> filters = new ArrayList<>();
        if (!query.statuses().isEmpty()) {
            filters.add(IncidentSpecifications.statusIn(query.statuses()));
        }
        if (!query.severities().isEmpty()) {
            filters.add(IncidentSpecifications.severityIn(query.severities()));
        }
        if (query.open() != null) {
            filters.add(IncidentSpecifications.open(query.open()));
        }
        if (query.serviceId() != null) {
            filters.add(IncidentSpecifications.service(query.serviceId()));
        }
        if (query.unmapped() != null) {
            filters.add(IncidentSpecifications.unmapped(query.unmapped()));
        }
        if (query.environment() != null && !query.environment().isBlank()) {
            // Same normaliser as ingestion (D-33): ?environment=prod finds the stored PRODUCTION.
            filters.add(IncidentSpecifications.environment(ServiceLookup.canonicalEnvironment(query.environment())));
        }
        if (query.owningTeamId() != null) {
            filters.add(IncidentSpecifications.owningTeam(query.owningTeamId()));
        }
        if (query.assigneeId() != null) {
            filters.add(IncidentSpecifications.assignee(query.assigneeId()));
        }
        if (query.source() != null) {
            filters.add(IncidentSpecifications.source(query.source()));
        }
        if (query.q() != null && !query.q().isBlank()) {
            filters.add(IncidentSpecifications.matches(query.q()));
        }
        if (query.from() != null) {
            filters.add(IncidentSpecifications.createdFrom(query.from()));
        }
        if (query.to() != null) {
            filters.add(IncidentSpecifications.createdTo(query.to()));
        }
        Page<Incident> page = incidents.findAll(Specification.allOf(filters), safePage(pageable));
        return new PageImpl<>(views.summaries(page.getContent()), page.getPageable(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public IncidentDetail get(UUID id, Collection<String> callerPermissions) {
        return views.detail(load(id), callerPermissions);
    }

    /** Timeline in chronological order (oldest first), paged; default page size 100. */
    @Transactional(readOnly = true)
    public Page<TimelineEntryView> timeline(UUID id, Pageable pageable) {
        Incident incident = load(id);
        Pageable chronological = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.asc("eventAt"), Sort.Order.asc("id")));
        Page<IncidentTimelineEntry> page = timeline.findByIncidentId(incident.getId(), chronological);
        return new PageImpl<>(views.timeline(page.getContent()), page.getPageable(), page.getTotalElements());
    }

    /** {@link Sorting#restrict} with "newest first" as the default instead of ascending. */
    private static Pageable safePage(Pageable pageable) {
        if (!pageable.getSort().isSorted()) {
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("incidentNo")));
        }
        return Sorting.restrict(pageable, SORTABLE, "createdAt");
    }

    private Incident load(UUID id) {
        return incidents.findById(id)
                .orElseThrow(() -> new NotFoundException(IncidentErrorCodes.INCIDENT_NOT_FOUND,
                        "Incident " + id + " not found"));
    }
}
