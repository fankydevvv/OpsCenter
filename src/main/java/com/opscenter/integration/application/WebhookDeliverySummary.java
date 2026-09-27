package com.opscenter.integration.application;

import java.util.List;
import java.util.UUID;

import com.opscenter.alert.application.IngestionResult;
import com.opscenter.alert.domain.IngestionOutcome;
import com.opscenter.incident.application.CorrelationResult;
import com.opscenter.servicecatalog.application.MappingStatus;

/**
 * Answer of {@code POST /api/v1/integrations/alertmanager/webhook} (blueprint §7.2). Alertmanager
 * only looks at the status code; the body exists for humans and tests (the demo script prints it).
 *
 * @param deliveryId    id of the processing that produced this answer (also on a replay)
 * @param replayed      {@code true} = this exact delivery was processed before; nothing was changed (D-43)
 * @param received      number of alerts in the delivery; {@code items} has one entry per logical alert, so
 *                      alerts of the delivery sharing a fingerprint appear once (folded, see CollapsedEvent)
 * @param alertsCreated new logical alerts (CREATED, REFIRED, RESOLVED_UNKNOWN)
 * @param archived      the raw body is in object storage (D-42)
 */
public record WebhookDeliverySummary(UUID deliveryId, boolean replayed, int received, long alertsCreated,
                                     long alertsDeduplicated, long alertsResolved, long incidentsCreated,
                                     long incidentsUpdated, long incidentsReopened, long unmappedAlerts,
                                     boolean archived, List<Item> items) {

    /** One logical alert of the delivery (one fingerprint). */
    public record Item(String fingerprint, UUID alertId, IngestionOutcome outcome, MappingStatus mappingStatus,
                       UUID incidentId, String incidentNo) {
    }

    public static WebhookDeliverySummary of(IngestionResult result, boolean replayed) {
        List<Item> items = result.items().stream()
                .map(i -> new Item(i.fingerprint(), i.alertId(), i.outcome(), i.mappingStatus(), i.incidentId(),
                        i.incidentNo()))
                .toList();
        return new WebhookDeliverySummary(result.deliveryId(), replayed, result.received(),
                result.count(IngestionOutcome.CREATED, IngestionOutcome.REFIRED, IngestionOutcome.RESOLVED_UNKNOWN),
                result.count(IngestionOutcome.DEDUPLICATED),
                result.count(IngestionOutcome.RESOLVED),
                result.incidents(CorrelationResult.Action.CREATED.name()),
                result.incidents(CorrelationResult.Action.LINKED.name()),
                result.incidents(CorrelationResult.Action.REOPENED.name()),
                result.unmapped(),
                result.archived(),
                items);
    }
}
