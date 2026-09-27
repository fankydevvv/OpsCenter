package com.opscenter.alert;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import com.opscenter.integration.testsupport.WebhookIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Resolved" notifications and re-fires (blueprint §8.3, D-54, D-59, D-60): the source recovering is
 * written to the incident timeline but never resolves the incident by itself; the same group firing
 * again after the incident was resolved re-opens it.
 */
class AlertResolvedIT extends WebhookIntegrationTest {

    private JsonNode transition(String incidentId, String command, String token, Map<String, Object> body)
            throws Exception {
        MvcResult result = mvc.perform(jsonRequest(post("/api/v1/incidents/" + incidentId + "/" + command), token, body))
                .andExpect(status().isOk())
                .andReturn();
        return body(result);
    }

    private static Map<String, Object> body(Object... keysAndValues) {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            body.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return body;
    }

    @Test
    void resolvedNotification_resolvesTheAlert_andTellsTheIncident_withoutChangingItsStatus() throws Exception {
        String name = unique("Recovered-");
        Map<String, String> labels = labels(name, "odoo-erp", "DEV", "t:9100", "critical");
        JsonNode fired = deliver(payload(firing(labels, "down", T0)));
        String alertId = fired.at("/items/0/alertId").asString();
        String incidentId = fired.at("/items/0/incidentId").asString();

        Instant endsAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        JsonNode resolved = deliver(payload(resolved(labels, "down", T0, endsAt)));

        assertThat(resolved.at("/items/0/outcome").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.get("alertsResolved").asLong()).isEqualTo(1);
        assertThat(resolved.at("/items/0/incidentId").asString()).isEqualTo(incidentId);
        Map<String, Object> alert = jdbc.queryForMap("select status, resolved_at from alerts where id = ?::uuid", alertId);
        assertThat(alert.get("status")).isEqualTo("RESOLVED");
        assertThat(((Timestamp) alert.get("resolved_at")).toInstant()).isEqualTo(endsAt);
        // D-59: no automatic resolve - a person must write the root cause
        assertThat(jdbc.queryForObject("select status from incidents where id = ?::uuid", String.class, incidentId))
                .isEqualTo("OPEN");
        assertThat(timelineTypes(incidentId)).containsExactly("INCIDENT_CREATED", "ALERT_RESOLVED", "ALL_ALERTS_RESOLVED");
        assertThat(count("select count(*) from outbox_events where aggregate_id = ?::uuid and event_type = 'AlertResolved'",
                alertId)).isEqualTo(1);
        assertThat(count("select count(*) from outbox_events where aggregate_id = ?::uuid and event_type = 'IncidentUpdated' "
                + "and payload->>'change' = 'ALL_ALERTS_RESOLVED'", incidentId)).isEqualTo(1);
    }

    @Test
    void oneOfTwoAlertsResolved_isNoted_butNotAllResolved() throws Exception {
        String name = unique("Partial-");
        JsonNode fired = deliver(payload(firing(labels(name, "odoo-erp", "DEV", "a:1", "warning"), null, T0),
                firing(labels(name, "odoo-erp", "DEV", "b:1", "warning"), null, T0)));
        String incidentId = fired.at("/items/0/incidentId").asString();

        deliver(payload(resolved(labels(name, "odoo-erp", "DEV", "a:1", "warning"), null, T0, T0.plusSeconds(90)),
                firing(labels(name, "odoo-erp", "DEV", "b:1", "warning"), null, T0)));

        assertThat(timelineTypes(incidentId)).containsExactly("INCIDENT_CREATED", "ALERT_LINKED", "ALERT_RESOLVED");
    }

    @Test
    void repeatedResolvedNotice_isDeduplicated_andUnknownResolved_createsNoIncident() throws Exception {
        String name = unique("Flappy-");
        Map<String, String> labels = labels(name, "odoo-erp", "DEV", "t:1", "info");
        deliver(payload(firing(labels, null, T0)));
        deliver(payload(resolved(labels, null, T0, T0.plusSeconds(60))));
        // Alertmanager re-sends the resolved alert with a later notification of the group
        JsonNode again = deliver(payload(resolved(labels, null, T0, T0.plusSeconds(60))), "X-Webhook-Id", unique("r-"));
        assertThat(again.at("/items/0/outcome").asString()).isEqualTo("DEDUPLICATED");
        assertThat(count("select count(*) from alerts where alert_name = ?", name)).isEqualTo(1);

        String unknown = unique("NeverSeen-");
        JsonNode resolvedUnknown = deliver(payload(resolved(labels(unknown, "odoo-erp", "DEV", "t:1", "info"), null, T0,
                T0.plusSeconds(60))));
        assertThat(resolvedUnknown.at("/items/0/outcome").asString()).isEqualTo("RESOLVED_UNKNOWN");
        assertThat(resolvedUnknown.at("/items/0/incidentId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select status from alerts where alert_name = ?", String.class, unknown))
                .isEqualTo("RESOLVED");
        assertThat(count("select count(*) from incident_alerts ia join alerts a on a.id = ia.alert_id "
                + "where a.alert_name = ?", unknown)).isZero();
    }

    @Test
    void refireAfterTheIncidentWasResolved_reopensIt_asASystemTransition() throws Exception {
        String engineer = engineerToken();
        String name = unique("Comeback-");
        Map<String, String> labels = labels(name, "odoo-erp", "DEV", "t:9100", "warning");
        JsonNode fired = deliver(payload(firing(labels, "down", T0)));
        String incidentId = fired.at("/items/0/incidentId").asString();
        transition(incidentId, "acknowledge", engineer, body("version", 0));
        transition(incidentId, "start-investigation", engineer, body("version", 1));
        transition(incidentId, "mitigate", engineer, body("version", 2, "mitigation", "restart"));
        deliver(payload(resolved(labels, "down", T0, T0.plusSeconds(120))));
        JsonNode resolvedIncident = transition(incidentId, "resolve", engineer,
                body("version", 3, "rootCause", "OOM", "resolution", "more memory"));
        String acknowledgedAt = resolvedIncident.get("acknowledgedAt").asString();

        // the same alert fires again: new episode (REFIRED) -> the recently resolved incident comes back
        JsonNode refired = deliver(payload(firing(labels, "down again", T0.plusSeconds(3600))));

        assertThat(refired.at("/items/0/outcome").asString()).isEqualTo("REFIRED");
        assertThat(refired.at("/items/0/incidentId").asString()).isEqualTo(incidentId);
        assertThat(refired.get("incidentsReopened").asLong()).isEqualTo(1);
        assertThat(refired.at("/items/0/alertId").asString()).isNotEqualTo(fired.at("/items/0/alertId").asString());

        JsonNode reopened = incident(incidentId, engineer);
        assertThat(reopened.get("status").asString()).isEqualTo("REOPENED");
        assertThat(reopened.get("reopenedAt").isNull()).isFalse();
        assertThat(reopened.get("resolvedAt").isNull()).isTrue();
        assertThat(reopened.get("acknowledgedAt").asString()).isEqualTo(acknowledgedAt);
        assertThat(reopened.get("allowedActions").get(0).asString()).isEqualTo("START_INVESTIGATION");
        assertThat(reopened.get("alertCount").asLong()).isEqualTo(2);
        JsonNode lastChange = reopened.get("statusHistory").get(reopened.get("statusHistory").size() - 1);
        assertThat(lastChange.get("fromStatus").asString()).isEqualTo("RESOLVED");
        assertThat(lastChange.get("toStatus").asString()).isEqualTo("REOPENED");
        assertThat(lastChange.get("changedBy").isNull()).isTrue();
        assertThat(timelineTypes(incidentId)).contains("INCIDENT_REOPENED");
        assertThat(count("select count(*) from outbox_events where aggregate_id = ?::uuid and event_type = 'IncidentReopened'",
                incidentId)).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'INCIDENT_REOPENED' and resource_id = ?::uuid "
                + "and actor_id is null", incidentId)).isEqualTo(1);

        // the reopened incident is worked again from INVESTIGATING
        JsonNode investigating = transition(incidentId, "start-investigation", engineer,
                body("version", reopened.get("version").asLong()));
        assertThat(investigating.get("status").asString()).isEqualTo("INVESTIGATING");
    }
}
