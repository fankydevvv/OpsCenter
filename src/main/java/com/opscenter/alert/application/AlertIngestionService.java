package com.opscenter.alert.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.opscenter.alert.domain.Alert;
import com.opscenter.alert.domain.AlertEvents;
import com.opscenter.alert.domain.AlertFacts;
import com.opscenter.alert.domain.AlertIngestionBusyException;
import com.opscenter.alert.domain.AlertOccurrence;
import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.alert.domain.IngestionOutcome;
import com.opscenter.alert.infrastructure.AlertOccurrenceRepository;
import com.opscenter.alert.infrastructure.AlertRepository;
import com.opscenter.incident.application.AlertCorrelationRequest;
import com.opscenter.incident.application.CorrelationResult;
import com.opscenter.incident.application.IncidentCorrelationService;
import com.opscenter.incident.application.IncidentRef;
import com.opscenter.incident.application.ResolvedAlertNotice;
import com.opscenter.incident.domain.IncidentSource;
import com.opscenter.servicecatalog.application.MappingStatus;
import com.opscenter.servicecatalog.application.ResolvedService;
import com.opscenter.servicecatalog.application.ServiceLookup;
import com.opscenter.shared.application.OutboxAppender;
import com.opscenter.shared.application.lock.DistributedLock;
import com.opscenter.shared.application.lock.LockTimeoutException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * The heart of Sprint 2: turns canonical alert events into alerts and incidents (FR-ALT-01..05,
 * blueprint §4.2, §8; D-46..D-51, D-54, D-59).
 * <p>
 * Runs inside the business transaction opened by {@code IdempotencyService}
 * ({@code Propagation.MANDATORY}), so the idempotency key, the alerts, their occurrences, the
 * incident changes, the audit lines and the outbox events commit - or roll back - together.
 * <p>
 * <b>Concurrency.</b> Two deliveries about the same alert group can arrive at the same moment
 * (Alertmanager HA pairs, retries, two instances of a target failing together). Without care both
 * read "no FIRING alert, no open incident" and both create one. Therefore, before reading anything,
 * the ingestion locks every <em>correlation key</em> of the delivery ({@link DistributedLock}: Redis
 * {@code SET NX PX}, PostgreSQL advisory lock when Redis is down), in sorted order so two deliveries
 * can never deadlock. The lock is held until after commit, so the second delivery reads what the
 * first one committed and takes the dedup/link path. A group-level lock (not a fingerprint lock)
 * covers both races: duplicates of one alert and sibling alerts racing to open the same incident.
 * The partial unique indexes remain the final guarantee (D-51).
 */
@Service
public class AlertIngestionService {

    private final AlertRepository alerts;
    private final AlertOccurrenceRepository occurrences;
    private final ServiceLookup serviceLookup;
    private final IncidentCorrelationService correlation;
    private final DistributedLock lock;
    private final OutboxAppender outbox;
    private final AlertProperties properties;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meters;
    private final Clock clock;

    public AlertIngestionService(AlertRepository alerts, AlertOccurrenceRepository occurrences,
                                 ServiceLookup serviceLookup, IncidentCorrelationService correlation,
                                 DistributedLock lock, OutboxAppender outbox, AlertProperties properties,
                                 JsonMapper jsonMapper, MeterRegistry meters, Clock clock) {
        this.alerts = alerts;
        this.occurrences = occurrences;
        this.serviceLookup = serviceLookup;
        this.correlation = correlation;
        this.lock = lock;
        this.outbox = outbox;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * Ingests one delivery. Events sharing a fingerprint are first folded into one decision
     * ({@link CollapsedEvent}: firing wins over resolved, the most severe firing event represents
     * them), then every fingerprint is processed in the order it first appears.
     *
     * @throws AlertIngestionBusyException 503 when a group lock stayed busy longer than the wait time
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IngestionResult ingest(List<CanonicalAlertEvent> events, DeliveryContext delivery) {
        List<CollapsedEvent> decisions = CollapsedEvent.collapse(events);
        Set<String> lockKeys = decisions.stream()
                .map(decision -> "alert-group:" + decision.event().correlationKey())
                .collect(Collectors.toSet());
        try {
            lock.lockAllForTransaction(lockKeys, properties.lock().ttl(), properties.lock().waitTime());
        }
        catch (LockTimeoutException busy) {
            throw new AlertIngestionBusyException("Another delivery of the same alert group is being processed; "
                    + "retry shortly (" + busy.getMessage() + ")");
        }
        Instant now = clock.instant();
        List<IngestionItem> items = new ArrayList<>(decisions.size());
        for (CollapsedEvent decision : decisions) {
            IngestionItem item = decision.event().status() == AlertStatus.FIRING
                    ? firing(decision, delivery, now)
                    : resolved(decision, delivery, now);
            items.add(item);
            Counter.builder("opscenter.alerts.ingested")
                    .description("Alert notifications by ingestion outcome (blueprint §9.4)")
                    .tag("outcome", item.outcome().name())
                    .register(meters)
                    .increment();
        }
        return new IngestionResult(delivery.deliveryId(), events.size(), items, delivery.archived());
    }

    /**
     * Rebuilds the answer of an already processed delivery from its occurrence rows - what a replayed
     * webhook returns (D-43, TC-ALT-004).
     */
    @Transactional(readOnly = true)
    public IngestionResult rebuild(UUID deliveryId) {
        List<IngestionItem> items = new ArrayList<>();
        boolean archived = false;
        int received = 0;
        for (AlertOccurrence occurrence : occurrences.findBySourceEventId(deliveryId.toString())) {
            AlertPayloads.Occurrence payload = jsonMapper.readValue(occurrence.getPayload(), AlertPayloads.Occurrence.class);
            archived |= payload.rawRef() != null;
            received += payload.eventCount();
            items.add(new IngestionItem(payload.index(), payload.fingerprint(), occurrence.getAlertId(),
                    payload.outcome(), payload.mappingStatus(), payload.incidentId(), payload.incidentNo(),
                    payload.incidentAction()));
        }
        items.sort(Comparator.comparingInt(IngestionItem::index));
        return new IngestionResult(deliveryId, received, items, archived);
    }

    // --- firing -------------------------------------------------------------------------------

    private IngestionItem firing(CollapsedEvent decision, DeliveryContext delivery, Instant now) {
        CanonicalAlertEvent event = decision.event();
        String fingerprint = event.fingerprint();
        Optional<Alert> alreadyFiring = alerts.findByOrganizationIdAndFingerprintAndStatus(
                delivery.organizationId(), fingerprint, AlertStatus.FIRING);
        if (alreadyFiring.isPresent()) {
            // TC-DEDUP-001: same episode, notified again -> count it, nothing new is created.
            Alert alert = alreadyFiring.get();
            alert.observeAgain(now, event.severity(), event.summary(), event.externalAlertId(), rawSlice(event, delivery));
            alerts.saveAndFlush(alert);
            // The incident follows the alert's latest severity upwards (D-54), e.g. when the critical
            // rule of a warning/critical pair starts firing on an alert that was only a warning.
            Optional<IncidentRef> incident = correlation.recordRepeatedOccurrence(alert.getId(), alert.getAlertName(),
                    alert.getSeverity());
            return record(alert, decision, delivery, now, IngestionOutcome.DEDUPLICATED, incident.orElse(null), null);
        }

        // New episode. "Seen before" distinguishes a re-fire after recovery from a brand-new alert (D-47).
        boolean seenBefore = alerts.existsByOrganizationIdAndFingerprint(delivery.organizationId(), fingerprint);
        ResolvedService service = serviceLookup.resolve(delivery.organizationId(), event.serviceCode(),
                event.environment());
        Alert alert = Alert.fire(delivery.organizationId(), delivery.integrationSourceId(), event.sourceType(),
                service.serviceId(), facts(event, fingerprint), rawSlice(event, delivery), now);
        // Flush now: if a concurrent delivery slipped past the lock, uk_alerts_firing_fingerprint fails
        // HERE and the webhook retries the delivery once (it then takes the dedup branch, D-51).
        alerts.saveAndFlush(alert);

        CorrelationResult incident = correlation.correlate(new AlertCorrelationRequest(delivery.organizationId(),
                alert.getId(), alert.getAlertName(), alert.getInstance(), event.correlationKey(), event.severity(),
                event.severityDefaulted(), event.rawSeverity(), service.serviceId(), service.serviceCode(),
                // alert.getEnvironment(): already cut to the column length by AlertFacts (VARCHAR(50))
                service.owningTeamId(), alert.getEnvironment(), service.environmentRegistered(), title(event),
                event.description(), incidentSource(event), now));
        IngestionOutcome outcome = seenBefore ? IngestionOutcome.REFIRED : IngestionOutcome.CREATED;
        outbox.append(AlertEvents.AGGREGATE_TYPE, alert.getId(), AlertEvents.ALERT_RECEIVED,
                new AlertPayloads.AlertReceived(alert.getId(), fingerprint, alert.getAlertName(), alert.getSeverity(),
                        alert.getStatus(), alert.getServiceId(), alert.getServiceCode(), alert.getEnvironment(),
                        mapping(alert), incident.incidentId(), now));
        return record(alert, decision, delivery, now, outcome,
                new IncidentRef(incident.incidentId(), incident.incidentNo(), null), incident.action().name());
    }

    // --- resolved -----------------------------------------------------------------------------

    /** Only reached when EVERY event of the fingerprint in this delivery is resolved ({@link CollapsedEvent}). */
    private IngestionItem resolved(CollapsedEvent decision, DeliveryContext delivery, Instant now) {
        CanonicalAlertEvent event = decision.event();
        String fingerprint = event.fingerprint();
        Optional<Alert> firing = alerts.findByOrganizationIdAndFingerprintAndStatus(
                delivery.organizationId(), fingerprint, AlertStatus.FIRING);
        if (firing.isPresent()) {
            Alert alert = firing.get();
            Instant resolvedAt = resolvedAt(event, now);
            alert.resolve(resolvedAt, now, rawSlice(event, delivery));
            // Flushed before the incident module reads the alert statuses ("all alerts resolved?").
            alerts.saveAndFlush(alert);
            List<IncidentRef> incidents = correlation.recordAlertResolved(new ResolvedAlertNotice(alert.getId(),
                    alert.getAlertName(), alert.getInstance(), resolvedAt, incidentSource(event)));
            outbox.append(AlertEvents.AGGREGATE_TYPE, alert.getId(), AlertEvents.ALERT_RESOLVED,
                    new AlertPayloads.AlertResolved(alert.getId(), fingerprint, resolvedAt,
                            incidents.stream().map(IncidentRef::id).toList()));
            return record(alert, decision, delivery, now, IngestionOutcome.RESOLVED,
                    incidents.isEmpty() ? null : incidents.getFirst(), null);
        }

        Optional<Alert> latest = alerts.findFirstByOrganizationIdAndFingerprintOrderByLastSeenAtDesc(
                delivery.organizationId(), fingerprint);
        if (latest.isPresent()) {
            // Alertmanager re-sends resolved alerts with later notifications of the group: log, change nothing.
            return record(latest.get(), decision, delivery, now, IngestionOutcome.DEDUPLICATED, null, null);
        }

        // D-59: resolved without ever having been seen firing (OpsCenter was down): keep it, open no incident.
        ResolvedService service = serviceLookup.resolve(delivery.organizationId(), event.serviceCode(),
                event.environment());
        Alert alert = Alert.resolvedWithoutFiring(delivery.organizationId(), delivery.integrationSourceId(),
                event.sourceType(), service.serviceId(), facts(event, fingerprint), rawSlice(event, delivery),
                resolvedAt(event, now), now);
        alerts.saveAndFlush(alert);
        return record(alert, decision, delivery, now, IngestionOutcome.RESOLVED_UNKNOWN, null, null);
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * Writes ONE occurrence row for the folded events (with the decision, for replays) and returns the
     * answer item.
     */
    private IngestionItem record(Alert alert, CollapsedEvent decision, DeliveryContext delivery, Instant now,
                                 IngestionOutcome outcome, IncidentRef incident, String incidentAction) {
        CanonicalAlertEvent event = decision.event();
        MappingStatus mappingStatus = mapping(alert);
        UUID incidentId = incident == null ? null : incident.id();
        String incidentNo = incident == null ? null : incident.incidentNo();
        AlertPayloads.Occurrence payload = new AlertPayloads.Occurrence(delivery.deliveryId(), event.index(),
                alert.getFingerprint(), outcome, mappingStatus, incidentId, incidentNo, incidentAction,
                delivery.rawRef(), decision.indexes(), decision.eventIds());
        occurrences.save(new AlertOccurrence(alert.getId(), now, event.status(), delivery.deliveryId().toString(),
                jsonMapper.writeValueAsString(payload)));
        return new IngestionItem(event.index(), alert.getFingerprint(), alert.getId(), outcome, mappingStatus,
                incidentId, incidentNo, incidentAction);
    }

    private String rawSlice(CanonicalAlertEvent event, DeliveryContext delivery) {
        return jsonMapper.writeValueAsString(new AlertPayloads.RawSlice(event.labels(), event.annotations(),
                event.startsAt(), event.endsAt(), event.generatorUrl(), delivery.deliveryId(), delivery.rawRef()));
    }

    private static AlertFacts facts(CanonicalAlertEvent event, String fingerprint) {
        return new AlertFacts(event.alertName(), fingerprint, event.severity(), event.serviceCode(),
                event.environment(), event.instance(), event.summary(), event.externalAlertId());
    }

    /** {@code endsAt} of the notification, but never in the future (source clocks drift, R-33). */
    private static Instant resolvedAt(CanonicalAlertEvent event, Instant now) {
        Instant endsAt = event.endsAt();
        return endsAt == null || endsAt.isAfter(now) ? now : endsAt;
    }

    private static String title(CanonicalAlertEvent event) {
        return event.summary() == null || event.summary().isBlank() ? event.alertName() : event.summary();
    }

    private static MappingStatus mapping(Alert alert) {
        return alert.isMapped() ? MappingStatus.MAPPED : MappingStatus.UNMAPPED;
    }

    private static IncidentSource incidentSource(CanonicalAlertEvent event) {
        return IncidentSource.valueOf(event.sourceType().name());
    }
}
