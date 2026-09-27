package com.opscenter.servicecatalog.application;

import java.util.Map;

import com.opscenter.servicecatalog.domain.ServiceStatus;

/**
 * Input for one environment - inside {@code POST /services} and for
 * {@code POST /services/{id}/environments}. {@code environmentCode} may be an alias ({@code prod});
 * it is normalised before the uniqueness check (D-33). Blank URLs mean "none".
 */
public record EnvironmentSpec(String environmentCode, ServiceStatus status, String healthEndpoint,
                              String metricEndpoint, String dashboardUrl, Map<String, Object> metadata) {
}
