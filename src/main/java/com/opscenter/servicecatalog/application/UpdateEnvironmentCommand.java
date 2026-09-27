package com.opscenter.servicecatalog.application;

import java.util.Map;

import com.opscenter.servicecatalog.domain.ServiceStatus;

/**
 * Input of {@code PATCH /api/v1/service-environments/{id}}: {@code null} = unchanged, a blank URL
 * clears it, {@code active = false} soft-deletes the environment. The environment code itself is
 * immutable (it is part of the alert join key).
 */
public record UpdateEnvironmentCommand(ServiceStatus status, String healthEndpoint, String metricEndpoint,
                                       String dashboardUrl, Map<String, Object> metadata, Boolean active,
                                       long version) {
}
