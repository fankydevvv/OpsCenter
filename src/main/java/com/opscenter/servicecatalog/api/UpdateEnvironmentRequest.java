package com.opscenter.servicecatalog.api;

import java.util.Map;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.opscenter.servicecatalog.application.UpdateEnvironmentCommand;
import com.opscenter.servicecatalog.domain.ServiceStatus;

/**
 * Body of {@code PATCH /api/v1/service-environments/{id}}: omitted = unchanged, {@code ""} clears a
 * URL, {@code active: false} soft-deletes the environment, {@code version} is mandatory.
 */
public record UpdateEnvironmentRequest(
        ServiceStatus status,
        @Size(max = 500) @Pattern(regexp = ApiPatterns.HTTP_URL_OR_EMPTY, message = ApiPatterns.HTTP_URL_MESSAGE)
        String healthEndpoint,
        @Size(max = 500) @Pattern(regexp = ApiPatterns.HTTP_URL_OR_EMPTY, message = ApiPatterns.HTTP_URL_MESSAGE)
        String metricEndpoint,
        @Size(max = 500) @Pattern(regexp = ApiPatterns.HTTP_URL_OR_EMPTY, message = ApiPatterns.HTTP_URL_MESSAGE)
        String dashboardUrl,
        Map<String, Object> metadata,
        Boolean active,
        @NotNull Long version) {

    public UpdateEnvironmentCommand toCommand() {
        return new UpdateEnvironmentCommand(status, healthEndpoint, metricEndpoint, dashboardUrl, metadata, active, version);
    }
}
