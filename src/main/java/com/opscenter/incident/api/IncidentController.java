package com.opscenter.incident.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.opscenter.incident.application.IncidentCommandService;
import com.opscenter.incident.application.IncidentDetail;
import com.opscenter.incident.application.IncidentListQuery;
import com.opscenter.incident.application.IncidentQueryService;
import com.opscenter.incident.application.IncidentSummary;
import com.opscenter.incident.application.TimelineEntryView;
import com.opscenter.incident.domain.IncidentSource;
import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.shared.api.PageResponse;
import com.opscenter.shared.domain.Severity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/incidents} (04-API §7, blueprint §7.4).
 * <p>
 * There is deliberately no {@code PATCH /incidents/{id}} that could set {@code status}: every
 * transition is its own command endpoint with its own permission (04-API §7 "status is not updated
 * through a generic PATCH"). The controller binds, checks the permission with
 * {@code @PreAuthorize} and delegates; the state machine lives in the domain.
 */
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentQueryService queries;
    private final IncidentCommandService commands;

    public IncidentController(IncidentQueryService queries, IncidentCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    /**
     * Paged list. {@code status} and {@code severity} may be repeated ({@code ?status=OPEN&status=REOPENED});
     * {@code open=true} = not RESOLVED/VERIFIED/CLOSED; {@code unmapped=true} = no service (D-49).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('incident.read')")
    public PageResponse<IncidentSummary> list(@RequestParam(required = false) List<IncidentStatus> status,
                                              @RequestParam(required = false) List<Severity> severity,
                                              @RequestParam(required = false) Boolean open,
                                              @RequestParam(required = false) UUID serviceId,
                                              @RequestParam(required = false) Boolean unmapped,
                                              @RequestParam(required = false) String environment,
                                              @RequestParam(required = false) UUID owningTeamId,
                                              @RequestParam(required = false) UUID assigneeId,
                                              @RequestParam(required = false) IncidentSource source,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(required = false) Instant from,
                                              @RequestParam(required = false) Instant to,
                                              Pageable pageable) {
        IncidentListQuery query = new IncidentListQuery(status, severity, open, serviceId, unmapped, environment,
                owningTeamId, assigneeId, source, q, from, to);
        return PageResponse.from(queries.list(query, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('incident.read')")
    public IncidentDetail get(@PathVariable UUID id, Authentication authentication) {
        return queries.get(id, CallerPermissions.of(authentication));
    }

    /** Chronological timeline, 100 entries per page by default. */
    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAuthority('incident.read')")
    public PageResponse<TimelineEntryView> timeline(@PathVariable UUID id,
                                                    @PageableDefault(size = 100) Pageable pageable) {
        return PageResponse.from(queries.timeline(id, pageable));
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAuthority('incident.acknowledge')")
    public IncidentDetail acknowledge(@PathVariable UUID id, @Valid @RequestBody IncidentNoteRequest request,
                                      Authentication authentication) {
        return commands.acknowledge(id, request.version(), request.note(), CallerPermissions.of(authentication));
    }

    @PostMapping("/{id}/start-investigation")
    @PreAuthorize("hasAuthority('incident.investigate')")
    public IncidentDetail startInvestigation(@PathVariable UUID id, @Valid @RequestBody IncidentNoteRequest request,
                                             Authentication authentication) {
        return commands.startInvestigation(id, request.version(), request.note(),
                CallerPermissions.of(authentication));
    }

    @PostMapping("/{id}/mitigate")
    @PreAuthorize("hasAuthority('incident.mitigate')")
    public IncidentDetail mitigate(@PathVariable UUID id, @Valid @RequestBody MitigateIncidentRequest request,
                                   Authentication authentication) {
        return commands.mitigate(id, request.version(), request.mitigation(), CallerPermissions.of(authentication));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('incident.resolve')")
    public IncidentDetail resolve(@PathVariable UUID id, @Valid @RequestBody ResolveIncidentRequest request,
                                  Authentication authentication) {
        return commands.resolve(id, request.version(), request.rootCause(), request.resolution(),
                request.mitigation(), CallerPermissions.of(authentication));
    }

    /** Guarded in Sprint 2: 422 RECOVERY_VERIFICATION_REQUIRED from RESOLVED, 409 otherwise. */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('incident.close')")
    public IncidentDetail close(@PathVariable UUID id, @Valid @RequestBody IncidentNoteRequest request,
                                Authentication authentication) {
        return commands.close(id, request.version(), request.note(), CallerPermissions.of(authentication));
    }
}
