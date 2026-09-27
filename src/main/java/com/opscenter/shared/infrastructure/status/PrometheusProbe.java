package com.opscenter.shared.infrastructure.status;

import java.util.LinkedHashMap;
import java.util.Map;

import com.opscenter.shared.application.status.ComponentProbe;
import com.opscenter.shared.application.status.ProbeResult;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Prometheus: version from {@code GET /api/v1/status/buildinfo} and how many scrape targets are up
 * or down from {@code GET /api/v1/targets?state=active} - during the demo, stopping
 * {@code opscenter-demo-target} makes {@code targetsDown} go to 1 before the alert even fires.
 * No URL configured = UNKNOWN (R-32).
 */
@Component
public class PrometheusProbe implements ComponentProbe {

    private final HttpJsonProbeClient http;
    private final SystemProbeProperties properties;

    public PrometheusProbe(HttpJsonProbeClient http, SystemProbeProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "prometheus";
    }

    @Override
    public ProbeResult probe() throws Exception {
        String baseUrl = properties.prometheusUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return ProbeResult.unknown("Prometheus URL not configured (opscenter.system.probes.prometheus-url)");
        }
        JsonNode buildInfo = http.getJson(baseUrl, "/api/v1/status/buildinfo");
        String version = text(buildInfo.path("data").path("version"));
        JsonNode targets = http.getJson(baseUrl, "/api/v1/targets?state=active");
        int up = 0;
        int down = 0;
        for (JsonNode target : targets.path("data").path("activeTargets")) {
            if ("up".equals(text(target.path("health")))) {
                up++;
            }
            else {
                down++;
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("url", baseUrl);
        details.put("targetsUp", up);
        details.put("targetsDown", down);
        return ProbeResult.up(version, details);
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
