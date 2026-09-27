package com.opscenter.servicecatalog.application;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.servicecatalog.domain.ServiceStatus;

import tools.jackson.databind.JsonNode;

/**
 * Full representation of a service environment (blueprint §7.1) and the audit snapshot of
 * {@code SERVICE_ENVIRONMENT_*} actions.
 */
public record ServiceEnvironmentView(UUID id, UUID serviceId, String environmentCode, ServiceStatus status,
                                     String healthEndpoint, String metricEndpoint, String dashboardUrl,
                                     JsonNode metadata, boolean active, Instant createdAt, Instant updatedAt,
                                     long version) {
}
