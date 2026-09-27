package com.opscenter.alert.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.opscenter.alert.domain.Alert;
import com.opscenter.alert.domain.AlertErrorCodes;
import com.opscenter.alert.domain.AlertOccurrence;
import com.opscenter.alert.infrastructure.AlertOccurrenceRepository;
import com.opscenter.alert.infrastructure.AlertRepository;
import com.opscenter.alert.infrastructure.AlertSpecifications;
import com.opscenter.incident.application.IncidentLinkLookup;
import com.opscenter.servicecatalog.application.MappingStatus;
import com.opscenter.servicecatalog.application.ServiceLookup;
import com.opscenter.shared.application.Sorting;
import com.opscenter.shared.application.storage.ObjectStorage;
import com.opscenter.shared.application.storage.StoredObject;
import com.opscenter.shared.application.storage.StoredObjectRef;
import com.opscenter.shared.domain.NotFoundException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read use cases of alerts ({@code GET /api/v1/alerts*}, 04-API §6, blueprint §7.3), including the
 * download of the archived raw webhook from object storage (D-42).
 */
@Service
public class AlertQueryService {

    /** Properties a client may sort by; default "last seen, newest first". */
    static final Set<String> SORTABLE = Set.of("lastSeenAt", "firstSeenAt", "severity", "occurrenceCount");

    private final AlertRepository alerts;
    private final AlertOccurrenceRepository occurrences;
    private final AlertViews views;
    private final IncidentLinkLookup incidents;
    private final ObjectStorage objectStorage;
    /** Short read-only transactions for {@link #raw}, which must not hold a connection during the S3 call. */
    private final TransactionTemplate readOnlyTransaction;

    public AlertQueryService(AlertRepository alerts, AlertOccurrenceRepository occurrences, AlertViews views,
                             IncidentLinkLookup incidents, ObjectStorage objectStorage,
                             PlatformTransactionManager transactionManager) {
        this.alerts = alerts;
        this.occurrences = occurrences;
        this.views = views;
        this.incidents = incidents;
        this.objectStorage = objectStorage;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    @Transactional(readOnly = true)
    public Page<AlertSummary> list(AlertListQuery query, Pageable pageable) {
        List<Specification<Alert>> filters = new ArrayList<>();
        if (!query.statuses().isEmpty()) {
            filters.add(AlertSpecifications.statusIn(query.statuses()));
        }
        if (!query.severities().isEmpty()) {
            filters.add(AlertSpecifications.severityIn(query.severities()));
        }
        if (query.serviceId() != null) {
            filters.add(AlertSpecifications.service(query.serviceId()));
        }
        if (query.environment() != null && !query.environment().isBlank()) {
            // Same normaliser as ingestion (D-33): ?environment=prod finds the stored PRODUCTION.
            filters.add(AlertSpecifications.environment(ServiceLookup.canonicalEnvironment(query.environment())));
        }
        if (query.mappingStatus() != null) {
            filters.add(AlertSpecifications.mapped(query.mappingStatus() == MappingStatus.MAPPED));
        }
        if (query.sourceType() != null) {
            filters.add(AlertSpecifications.sourceType(query.sourceType()));
        }
        if (query.q() != null && !query.q().isBlank()) {
            filters.add(AlertSpecifications.matches(query.q()));
        }
        if (query.from() != null) {
            filters.add(AlertSpecifications.lastSeenFrom(query.from()));
        }
        if (query.to() != null) {
            filters.add(AlertSpecifications.lastSeenTo(query.to()));
        }
        if (query.incidentId() != null) {
            List<UUID> linked = incidents.alertIdsOfIncident(query.incidentId());
            if (linked.isEmpty()) {
                return Page.empty(safePage(pageable));
            }
            filters.add(AlertSpecifications.idIn(linked));
        }
        Page<Alert> page = alerts.findAll(Specification.allOf(filters), safePage(pageable));
        return new PageImpl<>(views.summaries(page.getContent()), page.getPageable(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AlertDetail get(UUID id) {
        return views.detail(load(id));
    }

    /** Newest alerts first - for the Operations Center. */
    @Transactional(readOnly = true)
    public List<AlertSummary> recent(int limit) {
        return views.summaries(alerts.findAll(PageRequest.of(0, limit,
                Sort.by(Sort.Order.desc("lastSeenAt")))).getContent());
    }

    /**
     * The verbatim webhook body that carried this alert, streamed from object storage (additive
     * endpoint, permission {@code alert.raw.read} - raw labels may be sensitive, R-28).
     *
     * @param occurrenceId a specific notification; {@code null} = the latest archived one
     * @throws NotFoundException {@code ALERT_RAW_PAYLOAD_UNAVAILABLE} when nothing was archived or the
     *                           object expired (retention, 03-DB §25)
     * @throws com.opscenter.shared.application.storage.ObjectStorageUnavailableException 503 when the store is down
     */
    public RawPayloadDownload raw(UUID id, UUID occurrenceId) {
        // 1. Find the reference in a short read-only transaction ...
        StoredObjectRef ref = readOnlyTransaction.execute(status -> {
            Alert alert = load(id);
            StoredObjectRef found = occurrenceId == null ? latestRef(alert) : occurrenceRef(alert, occurrenceId);
            if (found == null) {
                throw rawUnavailable(id, "no archived raw payload (object storage was unavailable when it arrived)");
            }
            return found;
        });
        // 2. ... and download OUTSIDE of it: the S3 call may take seconds, and no pooled JDBC connection
        // is held meanwhile (same rule as the write path, D-42).
        StoredObject stored = objectStorage.get(ref.key())
                .orElseThrow(() -> rawUnavailable(id, "the archived object no longer exists (retention)"));
        String shortHash = ref.sha256() == null ? "raw" : ref.sha256().substring(0, Math.min(12, ref.sha256().length()));
        return new RawPayloadDownload("alert-" + id + "-" + shortHash + ".json",
                stored.contentType() == null ? "application/json" : stored.contentType(), stored.content());
    }

    private StoredObjectRef latestRef(Alert alert) {
        AlertPayloads.RawSlice slice = views.slice(alert);
        if (slice != null && slice.rawRef() != null) {
            return slice.rawRef();
        }
        // the latest notification was not archived: fall back to the newest one that was
        return occurrences.findTop50ByAlertIdOrderByObservedAtDesc(alert.getId()).stream()
                .map(views::occurrencePayload)
                .filter(p -> p != null && p.rawRef() != null)
                .map(AlertPayloads.Occurrence::rawRef)
                .findFirst()
                .orElse(null);
    }

    private StoredObjectRef occurrenceRef(Alert alert, UUID occurrenceId) {
        Optional<AlertOccurrence> occurrence = occurrences.findById(occurrenceId)
                .filter(o -> o.getAlertId().equals(alert.getId()));
        return occurrence.map(views::occurrencePayload).map(AlertPayloads.Occurrence::rawRef).orElse(null);
    }

    private static NotFoundException rawUnavailable(UUID alertId, String reason) {
        return new NotFoundException(AlertErrorCodes.ALERT_RAW_PAYLOAD_UNAVAILABLE,
                "Raw payload of alert " + alertId + " is not available: " + reason);
    }

    private static Pageable safePage(Pageable pageable) {
        if (!pageable.getSort().isSorted()) {
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                    Sort.by(Sort.Order.desc("lastSeenAt")));
        }
        return Sorting.restrict(pageable, SORTABLE, "lastSeenAt");
    }

    private Alert load(UUID id) {
        return alerts.findById(id)
                .orElseThrow(() -> new NotFoundException(AlertErrorCodes.ALERT_NOT_FOUND, "Alert " + id + " not found"));
    }
}
