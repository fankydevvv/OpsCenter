package com.opscenter.alert.application;

import java.util.Objects;
import java.util.UUID;

import com.opscenter.alert.domain.AlertSourceType;
import com.opscenter.shared.application.storage.StoredObjectRef;

/**
 * Facts about the delivery (one webhook call) that every alert of it shares.
 *
 * @param deliveryId          id of this delivery = {@code alert_occurrences.source_event_id} = idempotency
 *                            {@code resource_id} (D-43)
 * @param integrationSourceId the registered source ({@code integration_sources.id})
 * @param organizationId      the source's organization - alerts, services and incidents are looked up in it
 * @param rawRef              where the raw body was archived, {@code null} when archiving failed (D-42)
 */
public record DeliveryContext(UUID deliveryId, UUID integrationSourceId, String sourceCode, UUID organizationId,
                              AlertSourceType sourceType, StoredObjectRef rawRef) {

    public DeliveryContext {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(sourceType, "sourceType");
    }

    public boolean archived() {
        return rawRef != null;
    }
}
