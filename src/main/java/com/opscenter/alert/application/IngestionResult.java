package com.opscenter.alert.application;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.opscenter.alert.domain.IngestionOutcome;
import com.opscenter.servicecatalog.application.MappingStatus;

/**
 * Outcome of one delivery: an item per logical alert (events of the delivery that share a
 * fingerprint are folded into one item, see {@link CollapsedEvent}) plus the counters of the
 * webhook answer (blueprint §7.2 {@code WebhookDeliverySummary}). Built either by the ingestion
 * itself or, for a replayed delivery, from its {@code alert_occurrences} rows.
 *
 * @param received number of alerts in the delivery (before folding)
 */
public record IngestionResult(UUID deliveryId, int received, List<IngestionItem> items, boolean archived) {

    public IngestionResult {
        items = List.copyOf(items);
    }

    /** One item per received alert (nothing was folded). */
    public IngestionResult(UUID deliveryId, List<IngestionItem> items, boolean archived) {
        this(deliveryId, items.size(), items, archived);
    }

    public long count(IngestionOutcome... outcomes) {
        List<IngestionOutcome> wanted = Arrays.asList(outcomes);
        return items.stream().filter(i -> wanted.contains(i.outcome())).count();
    }

    /** Distinct incidents on which this delivery performed {@code action} (CREATED/LINKED/REOPENED). */
    public long incidents(String action) {
        return items.stream()
                .filter(i -> action.equals(i.incidentAction()))
                .map(IngestionItem::incidentId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    public long unmapped() {
        return items.stream().filter(i -> i.mappingStatus() == MappingStatus.UNMAPPED).count();
    }
}
