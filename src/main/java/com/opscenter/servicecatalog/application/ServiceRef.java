package com.opscenter.servicecatalog.application;

import java.util.UUID;

import com.opscenter.servicecatalog.domain.ServiceStatus;

/**
 * What other modules may know about a service when they display one of their own rows (an alert or
 * incident list shows service code and name) - returned in batches by
 * {@link ServiceLookup#findRefs} (D-37).
 */
public record ServiceRef(UUID id, UUID organizationId, String code, String name, ServiceStatus status,
                         boolean active, UUID owningTeamId) {
}
