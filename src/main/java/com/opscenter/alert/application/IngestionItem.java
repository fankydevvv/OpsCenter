package com.opscenter.alert.application;

import java.util.UUID;

import com.opscenter.alert.domain.IngestionOutcome;
import com.opscenter.servicecatalog.application.MappingStatus;

/**
 * What happened to one alert of a delivery.
 *
 * @param index          position of the alert in the delivery
 * @param incidentId     incident the alert belongs to ({@code null} for RESOLVED_UNKNOWN)
 * @param incidentAction {@code CREATED}, {@code LINKED}, {@code REOPENED} when this delivery changed the
 *                       incident's membership, otherwise {@code null}
 */
public record IngestionItem(int index, String fingerprint, UUID alertId, IngestionOutcome outcome,
                            MappingStatus mappingStatus, UUID incidentId, String incidentNo, String incidentAction) {
}
