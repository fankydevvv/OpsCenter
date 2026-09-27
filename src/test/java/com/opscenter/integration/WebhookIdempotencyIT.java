package com.opscenter.integration;

import java.util.Map;

import com.opscenter.integration.testsupport.WebhookIntegrationTest;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 07-TC §21 TC-IDEMP-001 applied to the webhook (04-API §2.5, blueprint D-43): Alertmanager retries a
 * delivery it believes failed (timeout, 5xx) with the very same body. The retry must be answered
 * from the first result and must not create a second alert, occurrence or incident.
 */
class WebhookIdempotencyIT extends WebhookIntegrationTest {

    @Test
    void TC_IDEMP_001_sameWebhookReplayed_createsNoSecondIncident() throws Exception {
        String name = unique("Replay-");
        Map<String, Object> payload = payload(
                firing(labels(name, "odoo-erp", "DEV", "a:1", "critical"), "replay test", T0),
                firing(labels(name, "odoo-erp", "DEV", "b:1", "critical"), "replay test", T0));

        JsonNode first = deliver(payload);
        JsonNode replay1 = deliver(payload);
        JsonNode replay2 = deliver(payload);

        assertThat(first.get("replayed").asBoolean()).isFalse();
        assertThat(first.get("incidentsCreated").asLong()).isEqualTo(1);
        assertThat(first.get("incidentsUpdated").asLong()).isEqualTo(1);
        for (JsonNode replay : new JsonNode[] {replay1, replay2}) {
            assertThat(replay.get("replayed").asBoolean()).isTrue();
            assertThat(replay.get("deliveryId").asString()).isEqualTo(first.get("deliveryId").asString());
            assertThat(replay.get("items")).hasSize(2);
            assertThat(replay.at("/items/1/incidentNo").asString()).isEqualTo(first.at("/items/0/incidentNo").asString());
        }

        assertThat(count("select count(*) from alerts where alert_name = ?", name)).isEqualTo(2);
        assertThat(count("select count(distinct ia.incident_id) from incident_alerts ia join alerts a on a.id = ia.alert_id "
                + "where a.alert_name = ?", name)).isEqualTo(1);
        assertThat(count("select count(*) from alert_occurrences o join alerts a on a.id = o.alert_id "
                + "where a.alert_name = ?", name)).isEqualTo(2);
        assertThat(count("select occurrence_count from incidents where id = ?::uuid",
                first.at("/items/0/incidentId").asString())).isEqualTo(2);
        // the key is the SHA-256 of the body, scoped to the Alertmanager source, kept 15 minutes (D-43)
        Map<String, Object> key = jdbc.queryForMap("select status, resource_type, resource_id::text as resource, "
                + "expires_at - created_at < interval '16 minutes' as short_ttl from idempotency_keys "
                + "where integration_id = ?::uuid and resource_id = ?::uuid", ALERTMANAGER_SOURCE_ID,
                first.get("deliveryId").asString());
        assertThat(key).containsEntry("status", "COMPLETED").containsEntry("resource_type", "AlertmanagerDelivery")
                .containsEntry("short_ttl", true);
    }

    @Test
    void sameAlertWithAnotherDeliveryId_isProcessed_andDeduplicated() throws Exception {
        String name = unique("Repeat-");
        Map<String, Object> payload = payload(firing(labels(name, "odoo-erp", "DEV", "a:1", "warning"), null, T0));

        deliver(payload, "X-Webhook-Id", unique("am-"));
        JsonNode repeat = deliver(payload, "X-Webhook-Id", unique("am-"));

        // not a replay (other delivery id) - Alertmanager's periodic re-notification: counted, not duplicated
        assertThat(repeat.get("replayed").asBoolean()).isFalse();
        assertThat(repeat.at("/items/0/outcome").asString()).isEqualTo("DEDUPLICATED");
        assertThat(count("select occurrence_count from alerts where alert_name = ?", name)).isEqualTo(2);
    }
}
