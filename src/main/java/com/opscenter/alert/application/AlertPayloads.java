package com.opscenter.alert.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.alert.domain.IngestionOutcome;
import com.opscenter.servicecatalog.application.MappingStatus;
import com.opscenter.shared.application.storage.StoredObjectRef;
import com.opscenter.shared.domain.Severity;

/**
 * JSON shapes the alert module writes into JSONB columns and the outbox (blueprint §9.1, D-42, D-43).
 * Records make the stored structure explicit and let Jackson read it back type-safely.
 */
public final class AlertPayloads {

    private AlertPayloads() {
    }

    /**
     * {@code alerts.raw_payload}: a small, masked slice of the latest notification plus the reference
     * to the full raw body in object storage (the body itself never enters PostgreSQL, 03-DB §3.5).
     */
    public record RawSlice(Map<String, String> labels, Map<String, String> annotations, Instant startsAt,
                           Instant endsAt, String generatorUrl, UUID deliveryId, StoredObjectRef rawRef) {
    }

    /**
     * {@code alert_occurrences.payload}: what the ingestion decided for this notification - enough to
     * rebuild the webhook answer of a replayed delivery (D-43).
     *
     * @param index    position of the representative event in the delivery
     * @param indexes  positions of every event folded into this occurrence ({@link CollapsedEvent});
     *                 {@code null} in rows written before folding existed = just {@code index}
     * @param eventIds their deterministic canonical event ids (04-API §23.1), same order
     */
    public record Occurrence(UUID deliveryId, int index, String fingerprint, IngestionOutcome outcome,
                             MappingStatus mappingStatus, UUID incidentId, String incidentNo, String incidentAction,
                             StoredObjectRef rawRef, List<Integer> indexes, List<UUID> eventIds) {

        /** Number of delivery events this occurrence stands for. */
        public int eventCount() {
            return indexes == null || indexes.isEmpty() ? 1 : indexes.size();
        }
    }

    /** Outbox {@code AlertReceived} - a new logical alert (CREATED/REFIRED). */
    public record AlertReceived(UUID alertId, String fingerprint, String alertName, Severity severity,
                                AlertStatus status, UUID serviceId, String serviceCode, String environment,
                                MappingStatus mappingStatus, UUID incidentId, Instant occurredAt) {
    }

    /** Outbox {@code AlertResolved} - FIRING -> RESOLVED. */
    public record AlertResolved(UUID alertId, String fingerprint, Instant resolvedAt, List<UUID> incidentIds) {
    }
}
