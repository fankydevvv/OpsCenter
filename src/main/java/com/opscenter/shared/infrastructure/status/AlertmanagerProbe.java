package com.opscenter.shared.infrastructure.status;

import java.util.LinkedHashMap;
import java.util.Map;

import com.opscenter.shared.application.status.ComponentProbe;
import com.opscenter.shared.application.status.ProbeResult;
import com.opscenter.shared.application.status.WebhookActivityQuery;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Alertmanager: version and cluster status from {@code GET /api/v2/status}, plus
 * {@code lastWebhookAt} - when OpsCenter last accepted a webhook from it - if the integration module
 * provides a {@link WebhookActivityQuery}. "Alertmanager UP but lastWebhookAt old" is the classic
 * symptom of a wrong webhook URL or token. No URL configured = UNKNOWN (R-32).
 */
@Component
public class AlertmanagerProbe implements ComponentProbe {

    static final String SOURCE_CODE = "alertmanager";

    private final HttpJsonProbeClient http;
    private final SystemProbeProperties properties;
    private final ObjectProvider<WebhookActivityQuery> webhookActivity;

    public AlertmanagerProbe(HttpJsonProbeClient http, SystemProbeProperties properties,
                             ObjectProvider<WebhookActivityQuery> webhookActivity) {
        this.http = http;
        this.properties = properties;
        this.webhookActivity = webhookActivity;
    }

    @Override
    public String name() {
        return "alertmanager";
    }

    @Override
    public ProbeResult probe() throws Exception {
        String baseUrl = properties.alertmanagerUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return ProbeResult.unknown("Alertmanager URL not configured (opscenter.system.probes.alertmanager-url)");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("url", baseUrl);
        WebhookActivityQuery activity = webhookActivity.getIfAvailable();
        if (activity != null) {
            details.put("lastWebhookAt", activity.lastEventAt(SOURCE_CODE).orElse(null));
        }
        JsonNode status = http.getJson(baseUrl, "/api/v2/status");
        JsonNode version = status.path("versionInfo").path("version");
        JsonNode cluster = status.path("cluster").path("status");
        if (!cluster.isMissingNode() && !cluster.isNull()) {
            details.put("cluster", cluster.asString());
        }
        return ProbeResult.up(version.isMissingNode() || version.isNull() ? null : version.asString(), details);
    }
}
