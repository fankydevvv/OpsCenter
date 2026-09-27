package com.opscenter.shared.infrastructure.status;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code opscenter.system.probes.*} (D-63, R-32).
 *
 * @param timeout         budget of every probe (and therefore of the whole status request)
 * @param prometheusUrl   base URL of Prometheus as seen from the backend ({@code http://prometheus:9090}
 *                        in the container, {@code http://localhost:9090} on the host); blank = UNKNOWN
 * @param alertmanagerUrl base URL of Alertmanager; blank = UNKNOWN
 */
@Validated
@ConfigurationProperties(prefix = "opscenter.system.probes")
public record SystemProbeProperties(
        @NotNull @DefaultValue("PT2S") Duration timeout,
        String prometheusUrl,
        String alertmanagerUrl) {
}
