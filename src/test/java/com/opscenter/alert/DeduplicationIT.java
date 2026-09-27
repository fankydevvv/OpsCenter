package com.opscenter.alert;

import java.util.List;
import java.util.Map;

import com.opscenter.alert.application.IngestionRaces;
import com.opscenter.integration.testsupport.WebhookIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deduplication and grouping (07-TC §10 TC-DEDUP-001/002; FR-ALT-03/04; blueprint §8.2, §8.3, D-46,
 * D-47, D-54, D-57).
 */
class DeduplicationIT extends WebhookIntegrationTest {

    @Test
    void TC_DEDUP_001_sameFingerprint_incrementsTheOccurrence_insteadOfCreatingAnAlertOrIncident() throws Exception {
        String name = unique("HighCpu-");
        Map<String, String> labels = labels(name, "odoo-erp", "DEV", "app-1:9100", "warning");

        JsonNode first = deliver(payload(firing(labels, "cpu high", T0)), "X-Webhook-Id", unique("d-"));
        // Same alert again: a later startsAt, another annotation and another delivery id - none of them
        // is part of the fingerprint (D-46), so this is the same alert.
        JsonNode second = deliver(payload(firing(labels, "cpu still high", T0.plusSeconds(60))),
                "X-Webhook-Id", unique("d-"));

        assertThat(first.at("/items/0/outcome").asString()).isEqualTo("CREATED");
        assertThat(second.at("/items/0/outcome").asString()).isEqualTo("DEDUPLICATED");
        assertThat(second.get("alertsDeduplicated").asLong()).isEqualTo(1);
        assertThat(second.get("incidentsCreated").asLong()).isZero();
        assertThat(second.at("/items/0/alertId").asString()).isEqualTo(first.at("/items/0/alertId").asString());
        assertThat(second.at("/items/0/incidentId").asString()).isEqualTo(first.at("/items/0/incidentId").asString());

        String alertId = first.at("/items/0/alertId").asString();
        Map<String, Object> alert = jdbc.queryForMap("select occurrence_count, summary, last_seen_at > first_seen_at "
                + "as moved from alerts where id = ?::uuid", alertId);
        assertThat(alert).containsEntry("occurrence_count", 2L).containsEntry("summary", "cpu still high");
        assertThat(count("select count(*) from alerts where alert_name = ?", name)).isEqualTo(1);
        assertThat(count("select count(*) from alert_occurrences where alert_id = ?::uuid", alertId)).isEqualTo(2);

        // D-57: the incident counts the repeat, but its version (human optimistic lock) does not move
        String incidentId = first.at("/items/0/incidentId").asString();
        Map<String, Object> incident = jdbc.queryForMap("select occurrence_count, version from incidents where id = ?::uuid",
                incidentId);
        assertThat(incident).containsEntry("occurrence_count", 2L).containsEntry("version", 0L);
        assertThat(count("select count(*) from incidents where fingerprint = (select fingerprint from incidents "
                + "where id = ?::uuid)", incidentId)).isEqualTo(1);
        // dedup publishes no event (only a counter moved)
        assertThat(count("select count(*) from outbox_events where aggregate_id = ?::uuid", alertId)).isEqualTo(1);
    }

    @Test
    void TC_DEDUP_002_differentInstance_isANewAlert_groupedIntoTheSameIncident() throws Exception {
        String name = unique("NodeDown-");
        JsonNode first = deliver(payload(firing(labels(name, "odoo-erp", "DEV", "node-1:9100", "warning"), null, T0)));
        JsonNode second = deliver(payload(firing(labels(name, "odoo-erp", "DEV", "node-2:9100", "critical"), null, T0)));

        assertThat(second.at("/items/0/outcome").asString()).isEqualTo("CREATED");
        assertThat(second.at("/items/0/alertId").asString()).isNotEqualTo(first.at("/items/0/alertId").asString());
        assertThat(second.at("/items/0/fingerprint").asString()).isNotEqualTo(first.at("/items/0/fingerprint").asString());
        assertThat(second.at("/items/0/incidentId").asString()).isEqualTo(first.at("/items/0/incidentId").asString());
        assertThat(second.get("incidentsUpdated").asLong()).isEqualTo(1);
        assertThat(second.get("incidentsCreated").asLong()).isZero();

        String incidentId = first.at("/items/0/incidentId").asString();
        JsonNode incident = incident(incidentId, engineerToken());
        assertThat(incident.get("alertCount").asLong()).isEqualTo(2);
        assertThat(incident.get("occurrenceCount").asLong()).isEqualTo(2);
        List<String> relations = List.of(incident.at("/alerts/0/relationType").asString(),
                incident.at("/alerts/1/relationType").asString());
        assertThat(relations).containsExactly("TRIGGER", "CORRELATED");
        // D-54: grouping only raises the severity (warning P3 -> critical P1), with a note and a new version
        assertThat(incident.get("severity").asString()).isEqualTo("P1");
        assertThat(incident.get("version").asLong()).isEqualTo(1);
        assertThat(timelineTypes(incidentId)).containsExactly("INCIDENT_CREATED", "SEVERITY_RAISED", "ALERT_LINKED");
        assertThat(count("select count(*) from audit_logs where action = 'INCIDENT_SEVERITY_CHANGED' "
                + "and resource_id = ?::uuid", incidentId)).isEqualTo(1);
    }

    @Test
    void rulePair_criticalFiringAndWarningResolvedInOneDelivery_keepsTheAlertFiring() throws Exception {
        // Review finding: a warning/critical rule pair differs only in the severity label, so both map to
        // ONE OpsCenter fingerprint. Processed in array order, [critical firing, warning resolved]
        // resolved the alert that was still burning. Folded per fingerprint, firing wins.
        String name = unique("Pair-");
        JsonNode first = deliver(payload(firing(labels(name, "odoo-erp", "DEV", "p:1", "warning"), "warn", T0)));
        String alertId = first.at("/items/0/alertId").asString();
        String incidentId = first.at("/items/0/incidentId").asString();

        JsonNode pair = deliver(payload(
                firing(labels(name, "odoo-erp", "DEV", "p:1", "critical"), "critical now", T0.plusSeconds(60)),
                resolved(labels(name, "odoo-erp", "DEV", "p:1", "warning"), "warn", T0, T0.plusSeconds(60))));

        assertThat(pair.get("received").asInt()).isEqualTo(2);
        assertThat(pair.get("items").size()).isEqualTo(1);
        assertThat(pair.at("/items/0/outcome").asString()).isEqualTo("DEDUPLICATED");
        assertThat(pair.get("alertsResolved").asLong()).isZero();
        Map<String, Object> alert = jdbc.queryForMap("select status, severity, summary, occurrence_count from alerts "
                + "where id = ?::uuid", alertId);
        assertThat(alert).containsEntry("status", "FIRING").containsEntry("severity", "P1")
                .containsEntry("summary", "critical now").containsEntry("occurrence_count", 2L);
        // one occurrence row per fingerprint and delivery, listing both positions
        String deliveryId = pair.get("deliveryId").asString();
        assertThat(count("select count(*) from alert_occurrences where source_event_id = ?", deliveryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload->>'indexes' from alert_occurrences where source_event_id = ?",
                String.class, deliveryId)).isEqualTo("[0, 1]");
        assertThat(timelineTypes(incidentId)).doesNotContain("ALERT_RESOLVED", "ALL_ALERTS_RESOLVED");
        assertThat(count("select count(*) from outbox_events where aggregate_id = ?::uuid and event_type = 'AlertResolved'",
                alertId)).isZero();

        // the replay rebuilds the same answer, including the folded count
        JsonNode replay = deliver(payload(
                firing(labels(name, "odoo-erp", "DEV", "p:1", "critical"), "critical now", T0.plusSeconds(60)),
                resolved(labels(name, "odoo-erp", "DEV", "p:1", "warning"), "warn", T0, T0.plusSeconds(60))));
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(replay.get("received").asInt()).isEqualTo(2);
        assertThat(replay.get("items").size()).isEqualTo(1);
    }

    @Test
    void repeatedNotification_withAHigherSeverity_raisesTheOpenIncident() throws Exception {
        // Review finding: the dedup path raised the ALERT to P1 but left its incident at P3.
        String name = unique("Escalate-");
        Map<String, String> warning = labels(name, "odoo-erp", "DEV", "e:1", "warning");
        JsonNode first = deliver(payload(firing(warning, null, T0)));
        String incidentId = first.at("/items/0/incidentId").asString();

        JsonNode second = deliver(payload(firing(labels(name, "odoo-erp", "DEV", "e:1", "critical"), null,
                T0.plusSeconds(60))));

        assertThat(second.at("/items/0/outcome").asString()).isEqualTo("DEDUPLICATED");
        Map<String, Object> incident = jdbc.queryForMap("select severity, version, occurrence_count from incidents "
                + "where id = ?::uuid", incidentId);
        assertThat(incident).containsEntry("severity", "P1").containsEntry("version", 1L)
                .containsEntry("occurrence_count", 2L);
        assertThat(timelineTypes(incidentId)).containsExactly("INCIDENT_CREATED", "SEVERITY_RAISED");
        assertThat(count("select count(*) from audit_logs where action = 'INCIDENT_SEVERITY_CHANGED' "
                + "and resource_id = ?::uuid", incidentId)).isEqualTo(1);

        // a later, LESS severe repeat lowers the alert (latest wins, D-47) but never the incident (D-54)
        deliver(payload(firing(warning, null, T0.plusSeconds(120))));
        assertThat(jdbc.queryForObject("select severity from incidents where id = ?::uuid", String.class, incidentId))
                .isEqualTo("P1");
    }

    @Test
    void lessSevereSibling_neverLowersTheIncidentSeverity() throws Exception {
        String name = unique("Latency-");
        JsonNode first = deliver(payload(firing(labels(name, "odoo-erp", "DEV", "a:1", "critical"), null, T0)));
        deliver(payload(firing(labels(name, "odoo-erp", "DEV", "b:1", "info"), null, T0)));

        String incidentId = first.at("/items/0/incidentId").asString();
        assertThat(jdbc.queryForObject("select severity from incidents where id = ?::uuid", String.class, incidentId))
                .isEqualTo("P1");
        assertThat(timelineTypes(incidentId)).containsExactly("INCIDENT_CREATED", "ALERT_LINKED");
    }

    @Test
    void theDatabase_isTheFinalGuard_againstASecondFiringAlertOrOpenIncident() throws Exception {
        String name = unique("Guard-");
        JsonNode first = deliver(payload(firing(labels(name, "odoo-erp", "DEV", "a:1", "warning"), null, T0)));
        String alertId = first.at("/items/0/alertId").asString();
        String incidentId = first.at("/items/0/incidentId").asString();

        // D-51: even if the lock were lost, PostgreSQL refuses the duplicate (partial unique indexes)
        assertThatThrownBy(() -> jdbc.update("insert into alerts (id, organization_id, source_type, alert_name, "
                + "fingerprint, severity, status, first_seen_at, last_seen_at) select gen_random_uuid(), organization_id, "
                + "source_type, alert_name, fingerprint, severity, 'FIRING', now(), now() from alerts where id = ?::uuid",
                alertId)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_alerts_firing_fingerprint")
                .satisfies(race -> assertThat(IngestionRaces.isLostRace((RuntimeException) race))
                        .as("a unique violation of a race guard is retried once").isTrue());
        // ... but a deterministic data error is not a race (value too long, SQLState 22001)
        assertThatThrownBy(() -> jdbc.update("update alerts set environment = ? where id = ?::uuid", "E".repeat(60),
                alertId)).isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(IngestionRaces.isLostRace((RuntimeException) error)).isFalse());
        assertThatThrownBy(() -> jdbc.update("insert into incidents (id, incident_no, organization_id, title, severity, "
                + "status, source, fingerprint) select gen_random_uuid(), 'INC-999' || floor(random() * 900000 + 100000), "
                + "organization_id, title, severity, 'OPEN', source, fingerprint from incidents where id = ?::uuid",
                incidentId)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_incidents_open_correlation");
    }

    @Test
    void environmentAliases_giveTheSameFingerprint() throws Exception {
        String name = unique("Alias-");
        JsonNode first = deliver(payload(firing(labels(name, "odoo-erp", "prod", "a:1", "warning"), null, T0)));
        JsonNode second = deliver(payload(firing(labels(name, "ODOO-ERP", "PRODUCTION", "a:1", "warning"), null,
                T0.plusSeconds(30))));

        assertThat(second.at("/items/0/outcome").asString()).isEqualTo("DEDUPLICATED");
        assertThat(second.at("/items/0/fingerprint").asString()).isEqualTo(first.at("/items/0/fingerprint").asString());
    }
}
