package com.opscenter.incident;

import java.util.HashMap;
import java.util.Map;

import com.opscenter.integration.testsupport.WebhookIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Incident lifecycle (07-TC §11 TC-INC-001..010, TC-VER-004, TC-AUD-003, TC-RBAC-002; 02-SAD §9;
 * blueprint §8.6, D-55..D-57): every command goes through the real API with a real login, and each
 * transition must leave status, history, timeline, audit and outbox consistent.
 */
class IncidentLifecycleIT extends WebhookIntegrationTest {

    /** Opens a fresh incident through the webhook; returns its id. */
    private String openIncident() throws Exception {
        JsonNode summary = deliver(payload(firing(labels(unique("Lifecycle-"), "odoo-erp", "DEV", "h:1", "high"),
                "lifecycle test", T0)));
        return summary.at("/items/0/incidentId").asString();
    }

    private ResultActions command(String incidentId, String command, String token, Map<String, Object> body)
            throws Exception {
        return mvc.perform(jsonRequest(post("/api/v1/incidents/" + incidentId + "/" + command), token, body));
    }

    private JsonNode apply(String incidentId, String command, String token, Map<String, Object> body) throws Exception {
        MvcResult result = command(incidentId, command, token, body).andExpect(status().isOk()).andReturn();
        return body(result);
    }

    private static Map<String, Object> version(long version) {
        Map<String, Object> body = new HashMap<>();
        body.put("version", version);
        return body;
    }

    private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        body.put(key, value);
        return body;
    }

    @Test
    void TC_INC_001_alertCreatesAnOpenIncident_withHistoryAndAllowedActions() throws Exception {
        String id = openIncident();

        mvc.perform(get("/api/v1/incidents/" + id).header("Authorization", bearer(engineerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentNo").value(matchesPattern("INC-\\d{6,}")))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.severity").value("P2"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.acknowledgedAt").doesNotExist())
                .andExpect(jsonPath("$.statusHistory.length()").value(1))
                .andExpect(jsonPath("$.allowedActions", contains("ACKNOWLEDGE")));
    }

    @Test
    void TC_INC_003_acknowledge_setsAcknowledgedAt_andRecordsEverythingInOneTransaction() throws Exception {
        String id = openIncident();

        JsonNode acknowledged = apply(id, "acknowledge", engineerToken(), with(version(0), "note", "on it"));

        assertThat(acknowledged.get("status").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(acknowledged.get("acknowledgedAt").isNull()).isFalse();
        assertThat(acknowledged.get("version").asLong()).isEqualTo(1);
        assertThat(acknowledged.get("allowedActions").get(0).asString()).isEqualTo("START_INVESTIGATION");
        assertThat(acknowledged.at("/statusHistory/1/fromStatus").asString()).isEqualTo("OPEN");
        assertThat(acknowledged.at("/statusHistory/1/changedBy/username").asString()).isEqualTo("engineer.a");
        assertThat(acknowledged.at("/statusHistory/1/reason").asString()).isEqualTo("on it");
        assertThat(timelineTypes(id)).containsExactly("INCIDENT_CREATED", "STATUS_CHANGED");
        assertThat(count("select count(*) from outbox_events where aggregate_id = ?::uuid "
                + "and event_type = 'IncidentAcknowledged'", id)).isEqualTo(1);
    }

    @Test
    void TC_AUD_003_statusChange_isAuditedWithActorAndBeforeAfter() throws Exception {
        String id = openIncident();
        apply(id, "acknowledge", engineerToken(), version(0));

        Map<String, Object> audit = jdbc.queryForMap("select actor_id, organization_id, before_data->>'status' as before, "
                + "after_data->>'status' as after, request_id from audit_logs "
                + "where action = 'INCIDENT_ACKNOWLEDGED' and resource_id = ?::uuid", id);
        assertThat(audit).containsEntry("actor_id", ENGINEER_A_ID).containsEntry("organization_id", DEFAULT_ORG_ID)
                .containsEntry("before", "OPEN").containsEntry("after", "ACKNOWLEDGED");
        assertThat(audit.get("request_id")).isNotNull();
    }

    @Test
    void TC_INC_004_005_006_theFullPath_toResolved() throws Exception {
        String engineer = engineerToken();
        String id = openIncident();
        apply(id, "acknowledge", engineer, version(0));

        JsonNode investigating = apply(id, "start-investigation", engineer, version(1));
        assertThat(investigating.get("status").asString()).isEqualTo("INVESTIGATING");
        assertThat(investigating.get("investigatingAt").isNull()).isFalse();

        // TC-INC-005: mitigation text is required
        command(id, "mitigate", engineer, version(2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("mitigation")));
        // resolve is not allowed before mitigation (linear flow, D-55)
        command(id, "resolve", engineer, with(with(version(2), "rootCause", "disk"), "resolution", "cleaned"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCIDENT_INVALID_TRANSITION"));
        JsonNode mitigated = apply(id, "mitigate", engineer, with(version(2), "mitigation", "Restarted target"));
        assertThat(mitigated.get("status").asString()).isEqualTo("MITIGATED");
        assertThat(mitigated.get("mitigatedAt").isNull()).isFalse();
        assertThat(mitigated.get("mitigationSummary").asString()).isEqualTo("Restarted target");

        // TC-INC-006: root cause AND resolution are mandatory
        command(id, "resolve", engineer, with(version(3), "resolution", "cleaned"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("rootCause")));
        JsonNode resolved = apply(id, "resolve", engineer,
                with(with(version(3), "rootCause", "Log rotation disabled"), "resolution", "Rotated and cleaned logs"));
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolvedAt").isNull()).isFalse();
        assertThat(resolved.get("rootCause").asString()).isEqualTo("Log rotation disabled");
        assertThat(resolved.get("allowedActions")).isEmpty();

        assertThat(jdbc.queryForList("select to_status from incident_status_history where incident_id = ?::uuid "
                + "order by changed_at", String.class, id))
                .containsExactly("OPEN", "ACKNOWLEDGED", "INVESTIGATING", "MITIGATED", "RESOLVED");
        assertThat(count("select count(*) from outbox_events where aggregate_id = ?::uuid and event_type in "
                + "('IncidentInvestigationStarted', 'IncidentMitigated', 'IncidentResolved')", id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select payload->>'rootCausePresent' from outbox_events "
                + "where aggregate_id = ?::uuid and event_type = 'IncidentResolved'", String.class, id)).isEqualTo("true");
    }

    @Test
    void TC_INC_007_closeWithoutRecoveryVerification_isBlockedWith422() throws Exception {
        String engineer = engineerToken();
        String id = openIncident();
        apply(id, "acknowledge", engineer, version(0));
        apply(id, "start-investigation", engineer, version(1));
        apply(id, "mitigate", engineer, with(version(2), "mitigation", "m"));
        apply(id, "resolve", engineer, with(with(version(3), "rootCause", "r"), "resolution", "s"));

        command(id, "close", engineer, version(4))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RECOVERY_VERIFICATION_REQUIRED"));
        assertThat(jdbc.queryForObject("select status from incidents where id = ?::uuid", String.class, id))
                .isEqualTo("RESOLVED");
    }

    @Test
    void TC_VER_004_closeFromAnOpenState_isAnInvalidTransition() throws Exception {
        String engineer = engineerToken();
        String id = openIncident();
        apply(id, "acknowledge", engineer, version(0));
        apply(id, "start-investigation", engineer, version(1));

        command(id, "close", engineer, version(2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCIDENT_INVALID_TRANSITION"));
        // acknowledging twice is invalid as well
        command(id, "acknowledge", engineer, version(2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCIDENT_INVALID_TRANSITION"));
    }

    @Test
    void TC_INC_010_staleVersion_is409_andChangesNothing() throws Exception {
        String engineer = engineerToken();
        String id = openIncident();
        apply(id, "acknowledge", engineer, version(0));

        command(id, "start-investigation", engineer, version(0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCIDENT_VERSION_CONFLICT"));
        assertThat(jdbc.queryForObject("select status from incidents where id = ?::uuid", String.class, id))
                .isEqualTo("ACKNOWLEDGED");
        assertThat(count("select count(*) from incident_status_history where incident_id = ?::uuid", id)).isEqualTo(2);
    }

    @Test
    void TC_RBAC_002_coordinatorMayAcknowledge_butNotInvestigate() throws Exception {
        String coordinator = coordinatorToken();
        String id = openIncident();

        JsonNode acknowledged = apply(id, "acknowledge", coordinator, version(0));
        assertThat(acknowledged.get("status").asString()).isEqualTo("ACKNOWLEDGED");
        // the state machine allows START_INVESTIGATION, the coordinator's permissions do not
        mvc.perform(get("/api/v1/incidents/" + id).header("Authorization", bearer(coordinator)))
                .andExpect(jsonPath("$.allowedActions", empty()));
        command(id, "start-investigation", coordinator, version(1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));
    }

    @Test
    void unknownIncident_is404_andMissingBody_is400() throws Exception {
        String engineer = engineerToken();
        command("00000000-0000-4000-8000-00000000beef", "acknowledge", engineer, version(0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
        command(openIncident(), "acknowledge", engineer, Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("version")));
    }
}
