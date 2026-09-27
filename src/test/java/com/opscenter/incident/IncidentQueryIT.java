package com.opscenter.incident;

import com.opscenter.integration.testsupport.WebhookIntegrationTest;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Read side of incidents and alerts (04-API §6, §7; blueprint §7.3-§7.5): filters, whitelisted
 * sorting, pagination, the timeline endpoint and the Operations Center summary.
 */
class IncidentQueryIT extends WebhookIntegrationTest {

    @Test
    void incidentList_filtersSortsAndPaginates() throws Exception {
        String prefix = unique("Qry");
        deliver(payload(firing(labels(prefix + "-A", "odoo-erp", "DEV", "a:1", "critical"), null, T0)));
        deliver(payload(firing(labels(prefix + "-B", "no-such-svc", "DEV", "b:1", "info"), null, T0)));
        String engineer = engineerToken();

        mvc.perform(get("/api/v1/incidents?q=" + prefix + "&sort=severity,asc").header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].severity", contains("P1", "P4")));
        mvc.perform(get("/api/v1/incidents?q=" + prefix + "&severity=P4&severity=P3").header("Authorization", bearer(engineer)))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].title").value("[UNMAPPED] " + prefix + "-B"));
        mvc.perform(get("/api/v1/incidents?q=" + prefix + "&serviceId=" + ODOO_SERVICE_ID + "&status=OPEN&open=true")
                        .header("Authorization", bearer(engineer)))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].service.code").value("odoo-erp"))
                .andExpect(jsonPath("$.items[0].owningTeam.code").value("PLATFORM"))
                .andExpect(jsonPath("$.items[0].alertCount").value(1));
        mvc.perform(get("/api/v1/incidents?q=" + prefix + "&open=false").header("Authorization", bearer(engineer)))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/incidents?q=" + prefix + "&size=1&page=1").header("Authorization", bearer(engineer)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
        // 04-API §2.4: only whitelisted sort properties
        mvc.perform(get("/api/v1/incidents?sort=rootCause").header("Authorization", bearer(engineer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/incidents?status=NOPE").header("Authorization", bearer(engineer)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void timeline_isChronological_andPaged() throws Exception {
        String name = unique("Tl-");
        JsonNode summary = deliver(payload(firing(labels(name, "unknown-svc", "DEV", "a:1", null), null, T0)));
        String id = summary.at("/items/0/incidentId").asString();

        mvc.perform(get("/api/v1/incidents/" + id + "/timeline").header("Authorization", bearer(engineerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[*].eventType",
                        contains("INCIDENT_CREATED", "SERVICE_UNRESOLVED", "SEVERITY_DEFAULTED")))
                .andExpect(jsonPath("$.items[0].source").value("ALERTMANAGER"))
                .andExpect(jsonPath("$.items[0].metadata.mappingStatus").value("UNMAPPED"))
                .andExpect(jsonPath("$.items[1].source").value("SYSTEM"));
        mvc.perform(get("/api/v1/incidents/" + id + "/timeline?size=1&page=2").header("Authorization", bearer(engineerToken())))
                .andExpect(jsonPath("$.items[0].eventType").value("SEVERITY_DEFAULTED"));
        mvc.perform(get("/api/v1/incidents/00000000-0000-4000-8000-00000000beef/timeline")
                        .header("Authorization", bearer(engineerToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
    }

    @Test
    void alertList_filtersByStatusSeverityMappingAndIncident() throws Exception {
        String prefix = unique("AlQ");
        JsonNode first = deliver(payload(firing(labels(prefix + "-X", "odoo-erp", "DEV", "a:1", "critical"), null, T0),
                firing(labels(prefix + "-X", "odoo-erp", "DEV", "b:1", "critical"), null, T0)));
        deliver(payload(resolved(labels(prefix + "-X", "odoo-erp", "DEV", "b:1", "critical"), null, T0,
                T0.plusSeconds(10))));
        String incidentId = first.at("/items/0/incidentId").asString();
        String engineer = engineerToken();

        mvc.perform(get("/api/v1/alerts?q=" + prefix + "&status=FIRING").header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].instance").value("a:1"))
                .andExpect(jsonPath("$.items[0].mappingStatus").value("MAPPED"))
                .andExpect(jsonPath("$.items[0].primaryIncident.id").value(incidentId));
        mvc.perform(get("/api/v1/alerts?incidentId=" + incidentId + "&sort=occurrenceCount,desc")
                        .header("Authorization", bearer(engineer)))
                .andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/api/v1/alerts?q=" + prefix + "&mappingStatus=UNMAPPED").header("Authorization", bearer(engineer)))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/alerts?sort=rawPayload").header("Authorization", bearer(engineer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/alerts/00000000-0000-4000-8000-00000000beef").header("Authorization", bearer(engineer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ALERT_NOT_FOUND"));
    }

    @Test
    void operationsSummary_countsOpenIncidentsAndFiringAlerts() throws Exception {
        String coordinator = coordinatorToken();
        JsonNode before = getJson("/api/v1/operations/summary", coordinator);

        String name = unique("Ops-");
        deliver(payload(firing(labels(name, "unregistered-svc", "DEV", "a:1", "high"), null, T0)));
        JsonNode after = getJson("/api/v1/operations/summary", coordinator);

        assertThat(after.at("/openIncidents/total").asLong()).isEqualTo(before.at("/openIncidents/total").asLong() + 1);
        assertThat(after.at("/openIncidents/bySeverity/P2").asLong())
                .isEqualTo(before.at("/openIncidents/bySeverity/P2").asLong() + 1);
        assertThat(after.at("/openIncidents/byStatus/OPEN").asLong())
                .isEqualTo(before.at("/openIncidents/byStatus/OPEN").asLong() + 1);
        assertThat(after.at("/openIncidents/unmapped").asLong()).isEqualTo(before.at("/openIncidents/unmapped").asLong() + 1);
        assertThat(after.at("/alerts/firing").asLong()).isEqualTo(before.at("/alerts/firing").asLong() + 1);
        assertThat(after.at("/alerts/unmappedFiring").asLong()).isEqualTo(before.at("/alerts/unmappedFiring").asLong() + 1);
        assertThat(after.at("/recentIncidents/0/title").asString()).isEqualTo("[UNMAPPED] " + name);
        assertThat(after.at("/recentAlerts/0/alertName").asString()).isEqualTo(name);
        assertThat(after.at("/openIncidents/bySeverity").size()).isEqualTo(4);
    }
}
