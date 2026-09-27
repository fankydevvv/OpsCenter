package com.opscenter.integration.testsupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.identity.testsupport.IdentityIntegrationTest;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base of the Sprint 2 end-to-end tests: the full application on Testcontainers (PostgreSQL migrated
 * with V001-V011 + seed-dev + seed-demo, Redis, MinIO), driven like Alertmanager drives it - a JSON
 * webhook with the shared bearer token - and like a user does - login, then the incident API.
 * <p>
 * Every test uses its own unique alert name, so fingerprints and correlation keys never collide
 * between tests that share the database (no cleanup needed).
 */
public abstract class WebhookIntegrationTest extends IdentityIntegrationTest {

    public static final String WEBHOOK = "/api/v1/integrations/alertmanager/webhook";
    /** {@code opscenter.integration.alertmanager.token} of application-test.yml. */
    public static final String TOKEN = "test-only-alertmanager-token-0123456789abcdef";
    /** Seeded by V008. */
    protected static final UUID ALERTMANAGER_SOURCE_ID = UUID.fromString("00000000-0000-4000-8000-000000000301");
    /** Seeded by db/seed-demo/V011_1 (service odoo-erp, environment DEV, team PLATFORM). */
    protected static final UUID ODOO_SERVICE_ID = UUID.fromString("00000000-0000-4000-8000-000000000401");

    protected static final Instant T0 = Instant.parse("2026-09-27T10:00:00Z");

    // --- payload builders (Alertmanager webhook v4) -----------------------------------------------

    /** Labels with {@code null} values left out. */
    protected static Map<String, String> labels(String alertName, String service, String environment, String instance,
                                                String severity) {
        Map<String, String> labels = new LinkedHashMap<>();
        putIfPresent(labels, "alertname", alertName);
        putIfPresent(labels, "service", service);
        putIfPresent(labels, "environment", environment);
        putIfPresent(labels, "instance", instance);
        putIfPresent(labels, "severity", severity);
        labels.put("job", "test-job");
        return labels;
    }

    protected static Map<String, Object> firing(Map<String, String> labels, String summary, Instant startsAt) {
        return alert("firing", labels, summary, startsAt, Instant.parse("0001-01-01T00:00:00Z"));
    }

    protected static Map<String, Object> resolved(Map<String, String> labels, String summary, Instant startsAt,
                                                  Instant endsAt) {
        return alert("resolved", labels, summary, startsAt, endsAt);
    }

    protected static Map<String, Object> alert(String status, Map<String, String> labels, String summary,
                                               Instant startsAt, Instant endsAt) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("status", status);
        alert.put("labels", labels);
        Map<String, String> annotations = new LinkedHashMap<>();
        putIfPresent(annotations, "summary", summary);
        alert.put("annotations", annotations);
        alert.put("startsAt", startsAt.toString());
        alert.put("endsAt", endsAt.toString());
        alert.put("generatorURL", "http://prometheus:9090/graph?g0.expr=up");
        alert.put("fingerprint", Integer.toHexString(labels.hashCode()));
        return alert;
    }

    @SafeVarargs
    protected static Map<String, Object> payload(Map<String, Object>... alerts) {
        List<Map<String, Object>> list = new ArrayList<>(Arrays.asList(alerts));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", "4");
        payload.put("groupKey", "{}:{alertname=\"test\"}");
        payload.put("truncatedAlerts", 0);
        payload.put("status", list.stream().anyMatch(a -> "firing".equals(a.get("status"))) ? "firing" : "resolved");
        payload.put("receiver", "opscenter");
        payload.put("groupLabels", Map.of());
        payload.put("commonLabels", Map.of());
        payload.put("commonAnnotations", Map.of());
        payload.put("externalURL", "http://alertmanager:9093");
        payload.put("alerts", list);
        return payload;
    }

    // --- webhook calls ------------------------------------------------------------------------------

    protected MockHttpServletRequestBuilder webhookRequest(byte[] body) {
        return post(WEBHOOK).header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    protected ResultActions postWebhook(Object payload, String... headerNamesAndValues) throws Exception {
        MockHttpServletRequestBuilder request = webhookRequest(json.writeValueAsBytes(payload));
        for (int i = 0; i + 1 < headerNamesAndValues.length; i += 2) {
            request.header(headerNamesAndValues[i], headerNamesAndValues[i + 1]);
        }
        return mvc.perform(request);
    }

    /** Posts and expects 200; returns the {@code WebhookDeliverySummary}. */
    protected JsonNode deliver(Object payload, String... headerNamesAndValues) throws Exception {
        MvcResult result = postWebhook(payload, headerNamesAndValues).andExpect(status().isOk()).andReturn();
        return body(result);
    }

    // --- reads ----------------------------------------------------------------------------------------

    protected JsonNode getJson(String path, String token) throws Exception {
        MvcResult result = mvc.perform(get(path).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result);
    }

    protected JsonNode incident(String incidentId, String token) throws Exception {
        return getJson("/api/v1/incidents/" + incidentId, token);
    }

    protected List<String> timelineTypes(String incidentId) {
        return jdbc.queryForList("select event_type from incident_timeline where incident_id = ?::uuid "
                + "order by event_at, id", String.class, incidentId);
    }

    protected int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
