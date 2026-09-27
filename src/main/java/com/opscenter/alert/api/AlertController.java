package com.opscenter.alert.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.alert.application.AlertDetail;
import com.opscenter.alert.application.AlertListQuery;
import com.opscenter.alert.application.AlertQueryService;
import com.opscenter.alert.application.AlertSummary;
import com.opscenter.alert.application.RawPayloadDownload;
import com.opscenter.alert.domain.AlertSourceType;
import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.servicecatalog.application.MappingStatus;
import com.opscenter.shared.api.PageResponse;
import com.opscenter.shared.domain.Severity;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/alerts} (04-API §6, blueprint §7.3). Alerts are created only by integrations
 * (the Alertmanager webhook); this controller is read-only. Manual alerts ({@code POST
 * /alerts/manual}) come in a later sprint.
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertQueryService queries;

    public AlertController(AlertQueryService queries) {
        this.queries = queries;
    }

    /**
     * Paged list; {@code status}/{@code severity} may be repeated; {@code mappingStatus=UNMAPPED} is the
     * "alerts without a catalog service" queue (D-48); default sort {@code lastSeenAt,desc}.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('alert.read')")
    public PageResponse<AlertSummary> list(@RequestParam(required = false) List<AlertStatus> status,
                                           @RequestParam(required = false) List<Severity> severity,
                                           @RequestParam(required = false) UUID serviceId,
                                           @RequestParam(required = false) String environment,
                                           @RequestParam(required = false) MappingStatus mappingStatus,
                                           @RequestParam(required = false) AlertSourceType sourceType,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) Instant from,
                                           @RequestParam(required = false) Instant to,
                                           @RequestParam(required = false) UUID incidentId,
                                           Pageable pageable) {
        AlertListQuery query = new AlertListQuery(status, severity, serviceId, environment, mappingStatus, sourceType,
                q, from, to, incidentId);
        return PageResponse.from(queries.list(query, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('alert.read')")
    public AlertDetail get(@PathVariable UUID id) {
        return queries.get(id);
    }

    /**
     * The verbatim archived webhook as a JSON attachment (D-42). A separate permission because raw
     * labels/annotations are not masked there (R-28) - ADMIN only by default.
     */
    @GetMapping("/{id}/raw")
    @PreAuthorize("hasAuthority('alert.raw.read')")
    public ResponseEntity<byte[]> raw(@PathVariable UUID id, @RequestParam(required = false) UUID occurrenceId) {
        RawPayloadDownload download = queries.raw(id, occurrenceId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.fileName()).build().toString())
                .body(download.content());
    }
}
