package com.opscenter.integration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.integration.testsupport.WebhookIntegrationTest;
import com.opscenter.shared.application.storage.ObjectStorage;
import com.opscenter.shared.application.storage.StoredObject;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Alert ingestion end to end (07-TC §9 TC-ALT-001..006; 04-API §6; blueprint §4.2, §7.2, D-39..D-49):
 * the Alertmanager webhook authenticated with the shared token, normalised, archived to MinIO,
 * stored, mapped to the catalog and turned into an incident - with outbox, audit and
 * {@code last_event_at} as side effects.
 */
class AlertmanagerWebhookIT extends WebhookIntegrationTest {

    @Autowired
    ObjectStorage objectStorage;

    @Test
    void TC_ALT_001_validWebhook_isNormalisedStoredArchivedAndOpensAnIncident() throws Exception {
        String name = unique("DiskFull-");
        Map<String, String> labels = labels(name, "Odoo-ERP", "dev", "demo-target:9100", "Critical");
        labels.put("db_password", "hunter2");
        byte[] body = json.writeValueAsBytes(payload(firing(labels, "odoo-erp disk almost full", T0)));

        MvcResult result = mvc.perform(webhookRequest(body).header("X-Request-Id", "tc-alt-001-" + name))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "tc-alt-001-" + name))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.received").value(1))
                .andExpect(jsonPath("$.alertsCreated").value(1))
                .andExpect(jsonPath("$.incidentsCreated").value(1))
                .andExpect(jsonPath("$.unmappedAlerts").value(0))
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.items[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.items[0].mappingStatus").value("MAPPED"))
                .andExpect(jsonPath("$.items[0].incidentNo", startsWith("INC-")))
                .andReturn();
        JsonNode summary = body(result);
        String alertId = summary.at("/items/0/alertId").asString();
        String incidentId = summary.at("/items/0/incidentId").asString();
        String deliveryId = summary.get("deliveryId").asString();

        // FR-ALT-02 normalisation as stored
        Map<String, Object> row = jdbc.queryForMap("select service_code, environment, severity, instance, status, "
                + "occurrence_count, service_id, integration_source_id, source_type, raw_payload::text as raw "
                + "from alerts where id = ?::uuid", alertId);
        assertThat(row).containsEntry("service_code", "odoo-erp").containsEntry("environment", "DEV")
                .containsEntry("severity", "P1").containsEntry("instance", "demo-target:9100")
                .containsEntry("status", "FIRING").containsEntry("occurrence_count", 1L)
                .containsEntry("service_id", ODOO_SERVICE_ID).containsEntry("integration_source_id", ALERTMANAGER_SOURCE_ID)
                .containsEntry("source_type", "ALERTMANAGER");
        String raw = (String) row.get("raw");
        assertThat(raw).contains("***").doesNotContain("hunter2");

        // D-42: the verbatim body is in MinIO, the database only keeps the reference
        JsonNode rawRef = json.readTree(raw).get("rawRef");
        assertThat(rawRef.get("key").asString()).startsWith("alertmanager/").endsWith(".json");
        Optional<StoredObject> archived = objectStorage.get(rawRef.get("key").asString());
        assertThat(archived).isPresent();
        assertThat(archived.get().content()).isEqualTo(body);
        assertThat(new String(archived.get().content(), StandardCharsets.UTF_8)).contains("hunter2");

        // one occurrence of this delivery, outbox events, audit, last_event_at
        assertThat(count("select count(*) from alert_occurrences where alert_id = ?::uuid and source_event_id = ?",
                alertId, deliveryId)).isEqualTo(1);
        assertThat(count("select count(*) from outbox_events where aggregate_id = ?::uuid and event_type = 'AlertReceived'",
                alertId)).isEqualTo(1);
        assertThat(count("select count(*) from outbox_events where aggregate_id = ?::uuid and event_type = 'IncidentCreated'",
                incidentId)).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'INTEGRATION_EVENT_RECEIVED' "
                + "and resource_id = ?::uuid and after_data->>'deliveryId' = ?", ALERTMANAGER_SOURCE_ID, deliveryId))
                .isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'INCIDENT_CREATED' and resource_id = ?::uuid "
                + "and actor_id is null", incidentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select last_event_at is not null from integration_sources where code = 'alertmanager'",
                Boolean.class)).isTrue();

        // the API view: masked labels, archive reference, incident link
        mvc.perform(get("/api/v1/alerts/" + alertId).header("Authorization", bearer(engineerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertName").value(name))
                .andExpect(jsonPath("$.serviceName").value("Odoo ERP (reference monitored system)"))
                .andExpect(jsonPath("$.labels.db_password").value("***"))
                .andExpect(jsonPath("$.labels.job").value("test-job"))
                .andExpect(jsonPath("$.annotations.summary").value("odoo-erp disk almost full"))
                .andExpect(jsonPath("$.rawArchive.archived").value(true))
                .andExpect(jsonPath("$.rawArchive.key").value(rawRef.get("key").asString()))
                .andExpect(jsonPath("$.occurrences[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.primaryIncident.id").value(incidentId))
                .andExpect(jsonPath("$.incidents[0].relationType").value("TRIGGER"))
                .andExpect(jsonPath("$.incidents[0].isPrimary").value(true));
    }

    @Test
    void TC_ALT_002_missingOrWrongToken_isRejectedWith401_andNothingIsStored() throws Exception {
        String name = unique("NoAuth-");
        byte[] body = json.writeValueAsBytes(payload(firing(labels(name, "odoo-erp", "DEV", "x:1", "critical"), null, T0)));

        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTEGRATION_AUTH_FAILED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        mvc.perform(post(WEBHOOK).header("Authorization", "Bearer " + TOKEN + "x")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTEGRATION_AUTH_FAILED"));
        // a perfectly valid USER token is still not the integration's token (separate filter chain, D-40)
        mvc.perform(post(WEBHOOK).header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTEGRATION_AUTH_FAILED"));

        assertThat(count("select count(*) from alerts where alert_name = ?", name)).isZero();
    }

    @Test
    void TC_ALT_003_alertWithoutAlertname_isRejectedWith400AndFieldErrors() throws Exception {
        Map<String, String> noName = labels(null, "odoo-erp", "DEV", "x:1", "critical");
        String name = unique("Valid-");

        postWebhook(payload(firing(labels(name, "odoo-erp", "DEV", "x:1", "warning"), null, T0),
                firing(noName, null, T0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("alerts[1].labels.alertname")));
        mvc.perform(webhookRequest("{\"alerts\": [".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));
        postWebhook(Map.of("version", "4", "alerts", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("alerts")));

        // the whole delivery is rejected: the valid alert of it was not stored either
        assertThat(count("select count(*) from alerts where alert_name = ?", name)).isZero();
    }

    @Test
    void TC_ALT_004_retriedDelivery_createsNoDuplicate_andReplaysTheAnswer() throws Exception {
        String name = unique("Retry-");
        Map<String, Object> payload = payload(firing(labels(name, "odoo-erp", "DEV", "a:1", "warning"), null, T0));

        JsonNode first = deliver(payload);
        JsonNode retry = deliver(payload);

        assertThat(retry.get("replayed").asBoolean()).isTrue();
        assertThat(retry.get("deliveryId").asString()).isEqualTo(first.get("deliveryId").asString());
        assertThat(retry.at("/items/0/alertId").asString()).isEqualTo(first.at("/items/0/alertId").asString());
        assertThat(retry.at("/items/0/outcome").asString()).isEqualTo("CREATED");
        assertThat(retry.at("/items/0/incidentNo").asString()).isEqualTo(first.at("/items/0/incidentNo").asString());
        assertThat(count("select count(*) from alerts where alert_name = ?", name)).isEqualTo(1);
        assertThat(count("select occurrence_count from alerts where alert_name = ?", name)).isEqualTo(1);
        assertThat(count("select count(*) from alert_occurrences o join alerts a on a.id = o.alert_id "
                + "where a.alert_name = ?", name)).isEqualTo(1);
    }

    @Test
    void explicitDeliveryKey_reusedWithAnotherBody_is422() throws Exception {
        String key = unique("delivery-");
        deliver(payload(firing(labels(unique("KeyA-"), "odoo-erp", "DEV", "a:1", "info"), null, T0)),
                "Idempotency-Key", key);

        postWebhook(payload(firing(labels(unique("KeyB-"), "odoo-erp", "DEV", "a:1", "info"), null, T0)),
                "Idempotency-Key", key)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void TC_ALT_005_alertIsMappedToTheCatalogService_andTheIncidentInheritsServiceTeamAndEnvironment() throws Exception {
        String name = unique("TargetDown-");
        JsonNode summary = deliver(payload(firing(labels(name, "odoo-erp", "DEV", "demo-target:9100", "critical"),
                "odoo-erp (DEV) scrape target demo-target:9100 is down", T0)));
        String incidentId = summary.at("/items/0/incidentId").asString();

        mvc.perform(get("/api/v1/incidents/" + incidentId).header("Authorization", bearer(engineerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("odoo-erp (DEV) scrape target demo-target:9100 is down"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.severity").value("P1"))
                .andExpect(jsonPath("$.source").value("ALERTMANAGER"))
                .andExpect(jsonPath("$.service.code").value("odoo-erp"))
                .andExpect(jsonPath("$.owningTeam.code").value("PLATFORM"))
                .andExpect(jsonPath("$.environment").value("DEV"))
                .andExpect(jsonPath("$.alerts[0].alertName").value(name))
                .andExpect(jsonPath("$.alerts[0].relationType").value("TRIGGER"))
                .andExpect(jsonPath("$.alerts[0].isPrimary").value(true))
                .andExpect(jsonPath("$.statusHistory[0].toStatus").value("OPEN"))
                .andExpect(jsonPath("$.statusHistory[0].changedBy").doesNotExist());
        assertThat(timelineTypes(incidentId)).containsExactly("INCIDENT_CREATED");
    }

    @Test
    void mappedServiceWithUnregisteredEnvironment_staysMapped_withATimelineNote() throws Exception {
        String name = unique("ProdOnly-");
        JsonNode summary = deliver(payload(firing(labels(name, "odoo-erp", "prod", "db:5432", "high"), null, T0)));

        assertThat(summary.at("/items/0/mappingStatus").asString()).isEqualTo("MAPPED");
        String incidentId = summary.at("/items/0/incidentId").asString();
        assertThat(timelineTypes(incidentId)).containsExactly("INCIDENT_CREATED", "ENVIRONMENT_NOT_REGISTERED");
        assertThat(jdbc.queryForObject("select environment from incidents where id = ?::uuid", String.class, incidentId))
                .isEqualTo("PRODUCTION");
    }

    @Test
    void TC_ALT_006_unmappedAlert_isVisible_andStillOpensAnIncident() throws Exception {
        String name = unique("Orphan-");
        JsonNode summary = deliver(payload(firing(labels(name, "no-such-service", "DEV", "h:1", null), null, T0)));
        String alertId = summary.at("/items/0/alertId").asString();
        String incidentId = summary.at("/items/0/incidentId").asString();

        assertThat(summary.at("/items/0/mappingStatus").asString()).isEqualTo("UNMAPPED");
        assertThat(summary.get("unmappedAlerts").asLong()).isEqualTo(1);
        String engineer = engineerToken();
        mvc.perform(get("/api/v1/alerts?mappingStatus=UNMAPPED&q=" + name).header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(alertId))
                .andExpect(jsonPath("$.items[0].serviceCode").value("no-such-service"))
                .andExpect(jsonPath("$.items[0].severity").value("P3"));
        mvc.perform(get("/api/v1/incidents?unmapped=true&q=" + name).header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(incidentId))
                .andExpect(jsonPath("$.items[0].title").value("[UNMAPPED] " + name))
                .andExpect(jsonPath("$.items[0].service").doesNotExist());
        // D-49 no alert is lost; D-45 missing severity label -> P3 with a note
        assertThat(timelineTypes(incidentId)).containsExactly("INCIDENT_CREATED", "SERVICE_UNRESOLVED",
                "SEVERITY_DEFAULTED");
    }

    @Test
    void longEnvironmentLabel_isCutToTheColumn_andTheDeliveryStillOpensAnIncident() throws Exception {
        // Review finding: a label longer than incidents.environment VARCHAR(50) failed the INSERT, the
        // delivery was retried as a "race" and answered 409 - Alertmanager drops 4xx, so every alert of
        // the group was lost for good (contradicting D-49).
        String name = unique("LongEnv-");
        String environment = "production-eu-west-1-cluster-a-payments-blue-green-canary-01";
        assertThat(environment).hasSizeGreaterThan(50);

        JsonNode summary = deliver(payload(firing(labels(name, "odoo-erp", environment, "h:1", "critical"), null, T0),
                firing(labels(unique("Sibling-"), "odoo-erp", "DEV", "h:2", "warning"), null, T0)));

        assertThat(summary.get("alertsCreated").asLong()).isEqualTo(2);
        String incidentId = summary.at("/items/0/incidentId").asString();
        String stored = jdbc.queryForObject("select environment from incidents where id = ?::uuid", String.class,
                incidentId);
        assertThat(stored).hasSize(50).startsWith("PRODUCTION_EU_WEST_1");
        assertThat(jdbc.queryForObject("select environment from alerts where alert_name = ?", String.class, name))
                .isEqualTo(stored);
    }

    @Test
    void unmappedIncident_adoptsTheService_onceItIsRegisteredInTheCatalog() throws Exception {
        // Review finding: SERVICE_UNRESOLVED tells the operator to register the service, but the open
        // [UNMAPPED] incident never picked it up.
        String name = unique("Adopt-");
        String code = unique("adopt-svc-");
        JsonNode first = deliver(payload(firing(labels(name, code, "DEV", "n:1", "warning"), null, T0)));
        String incidentId = first.at("/items/0/incidentId").asString();
        assertThat(first.at("/items/0/mappingStatus").asString()).isEqualTo("UNMAPPED");

        mvc.perform(jsonRequest(post("/api/v1/services"), adminToken(), Map.of("code", code, "name", "Adopted",
                        "owningTeamId", TEAM_PAYMENT_ID.toString())))
                .andExpect(status().isCreated());
        // a new alert of the same group (other instance) - now MAPPED
        JsonNode second = deliver(payload(firing(labels(name, code, "DEV", "n:2", "warning"), null, T0)));

        assertThat(second.at("/items/0/mappingStatus").asString()).isEqualTo("MAPPED");
        assertThat(second.at("/items/0/incidentId").asString()).isEqualTo(incidentId);
        JsonNode incident = incident(incidentId, engineerToken());
        assertThat(incident.get("title").asString()).isEqualTo(name);
        assertThat(incident.at("/service/code").asString()).isEqualTo(code);
        assertThat(incident.at("/owningTeam/id").asString()).isEqualTo(TEAM_PAYMENT_ID.toString());
        assertThat(incident.get("version").asLong()).isEqualTo(1);
        assertThat(timelineTypes(incidentId)).containsSubsequence("SERVICE_UNRESOLVED", "SERVICE_RESOLVED", "ALERT_LINKED");
        assertThat(count("select count(*) from audit_logs where action = 'INCIDENT_SERVICE_RESOLVED' "
                + "and resource_id = ?::uuid", incidentId)).isEqualTo(1);
    }

    @Test
    void listFilters_acceptEnvironmentAliases_likeTheIngestion() throws Exception {
        // Review finding: ?environment=prod returned an empty page although prod -> PRODUCTION on ingest.
        String name = unique("AliasFilter-");
        deliver(payload(firing(labels(name, "odoo-erp", "prd", "h:1", "warning"), null, T0)));
        String engineer = engineerToken();

        mvc.perform(get("/api/v1/alerts?environment=prod&q=" + name).header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].environment").value("PRODUCTION"));
        mvc.perform(get("/api/v1/incidents?environment=Prod&q=" + name).header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void occurrences_keepTheDeterministicCanonicalEventIds() throws Exception {
        // 04-API §23.1 "eventId + sourceCode must support idempotency": derived from the delivery key.
        String name = unique("EventId-");
        String key = unique("am-");
        deliver(payload(firing(labels(name, "odoo-erp", "DEV", "h:1", "info"), null, T0)), "X-Webhook-Id", key);

        String eventIds = jdbc.queryForObject("select o.payload->>'eventIds' from alert_occurrences o join alerts a "
                + "on a.id = o.alert_id where a.alert_name = ?", String.class, name);
        UUID expected = UUID.nameUUIDFromBytes(("alertmanager\u001F" + key + "\u001F0")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(eventIds).isEqualTo("[\"" + expected + "\"]");
    }

    @Test
    void payloadAboveOneMebibyte_is413() throws Exception {
        String huge = "x".repeat(1_100_000);
        postWebhook(payload(firing(labels(unique("Huge-"), "odoo-erp", "DEV", "h:1", "info"), huge, T0)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void rawPayloadDownload_isForAdministratorsOnly() throws Exception {
        String name = unique("RawDl-");
        byte[] body = json.writeValueAsBytes(payload(firing(labels(name, "odoo-erp", "DEV", "h:1", "info"), null, T0)));
        MvcResult result = mvc.perform(webhookRequest(body)).andExpect(status().isOk()).andReturn();
        String alertId = body(result).at("/items/0/alertId").asString();

        MvcResult download = mvc.perform(get("/api/v1/alerts/" + alertId + "/raw").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment")))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(body);

        mvc.perform(get("/api/v1/alerts/" + alertId + "/raw").header("Authorization", bearer(engineerToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));
    }
}
