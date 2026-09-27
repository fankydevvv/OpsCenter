package com.opscenter.servicecatalog.api;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.opscenter.servicecatalog.application.EnvironmentSpec;
import com.opscenter.servicecatalog.domain.ServiceStatus;

/**
 * One environment in {@code POST /api/v1/services} or the body of
 * {@code POST /api/v1/services/{id}/environments} (blueprint §7.1).
 */
public record EnvironmentRequest(
        @NotBlank @Pattern(regexp = ApiPatterns.ENVIRONMENT_CODE, message = ApiPatterns.ENVIRONMENT_CODE_MESSAGE)
        String environmentCode,
        ServiceStatus status,
        @Size(max = 500) @Pattern(regexp = ApiPatterns.HTTP_URL_OR_EMPTY, message = ApiPatterns.HTTP_URL_MESSAGE)
        String healthEndpoint,
        @Size(max = 500) @Pattern(regexp = ApiPatterns.HTTP_URL_OR_EMPTY, message = ApiPatterns.HTTP_URL_MESSAGE)
        String metricEndpoint,
        @Size(max = 500) @Pattern(regexp = ApiPatterns.HTTP_URL_OR_EMPTY, message = ApiPatterns.HTTP_URL_MESSAGE)
        String dashboardUrl,
        Map<String, Object> metadata) {

    public EnvironmentSpec toSpec() {
        return new EnvironmentSpec(environmentCode, status, healthEndpoint, metricEndpoint, dashboardUrl, metadata);
    }
}
