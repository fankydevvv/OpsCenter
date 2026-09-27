package com.opscenter.servicecatalog.application;

import java.util.UUID;

import com.opscenter.servicecatalog.domain.ServiceStatus;

/**
 * Filters of {@code GET /api/v1/services}; every field is optional except {@code active}
 * (default {@code true}: soft-deleted services are hidden unless asked for).
 *
 * @param environment raw environment code or alias; services with that ACTIVE environment
 */
public record ServiceListQuery(String q, ServiceStatus status, UUID owningTeamId, String environment, boolean active) {
}
