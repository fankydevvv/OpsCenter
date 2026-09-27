package com.opscenter.incident.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.incident.application.IncidentCommandService;
import com.opscenter.incident.application.IncidentDetail;
import com.opscenter.incident.application.IncidentListQuery;
import com.opscenter.incident.application.IncidentQueryService;
import com.opscenter.incident.application.OperationsSummary;
import com.opscenter.incident.application.OperationsSummaryService;
import com.opscenter.incident.domain.IncidentAction;
import com.opscenter.incident.domain.IncidentErrorCodes;
import com.opscenter.incident.domain.IncidentSource;
import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.Severity;
import com.opscenter.support.SecuritySliceConfig;
import com.opscenter.support.TestJwts;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of {@code /api/v1/incidents} and {@code /api/v1/operations/summary} on the real JWT
 * security chain (04-API §7, blueprint §6/§7.4): 401 without token, 403 per command permission,
 * 400 for invalid bodies/filters, and the caller's permissions handed to the use case.
 */
@WebMvcTest(controllers = {IncidentController.class, OperationsSummaryController.class})
@Import(SecuritySliceConfig.class)
@ActiveProfiles("test")
class IncidentControllerWebMvcTest {

    private static final UUID ID = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder jwtEncoder;
    @MockitoBean IncidentQueryService queries;
    @MockitoBean IncidentCommandService commands;
    @MockitoBean OperationsSummaryService summaries;

    private String token(String... permissions) {
        return "Bearer " + TestJwts.issue(jwtEncoder, UUID.randomUUID(), UUID.randomUUID(), "u", List.of("ENGINEER"),
                List.of(permissions), Instant.now(), Duration.ofMinutes(30));
    }

    private static IncidentDetail detail(IncidentStatus status) {
        return new IncidentDetail(ID, "INC-000001", "t", Severity.P1, null, status, IncidentSource.ALERTMANAGER, null,
                "DEV", null, null, 1, 1, Instant.now(), null, null, Instant.now(), 1, null, "fp", null, null, null, null,
                null, null, null, null, null, List.of(), List.of(), List.of(IncidentAction.START_INVESTIGATION));
    }

    @Test
    void withoutToken_everyEndpointIs401() throws Exception {
        mvc.perform(get("/api/v1/incidents")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/operations/summary")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/incidents/" + ID + "/acknowledge").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(queries, commands, summaries);
    }

    @Test
    void eachCommand_needsItsOwnPermission() throws Exception {
        String readOnly = token("incident.read", "incident.acknowledge", "incident.close");
        String[][] denied = {
                {"start-investigation", "{\"version\":1}"},
                {"mitigate", "{\"version\":1,\"mitigation\":\"m\"}"},
                {"resolve", "{\"version\":1,\"rootCause\":\"r\",\"resolution\":\"s\"}"}};
        for (String[] call : denied) {
            mvc.perform(post("/api/v1/incidents/" + ID + "/" + call[0]).header("Authorization", readOnly)
                            .contentType(MediaType.APPLICATION_JSON).content(call[1]))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));
        }
        mvc.perform(get("/api/v1/incidents").header("Authorization", token("alert.read")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(commands);
    }

    @Test
    void acknowledge_passesVersionNoteAndTheCallersPermissions() throws Exception {
        when(commands.acknowledge(eq(ID), anyLong(), any(), any())).thenReturn(detail(IncidentStatus.ACKNOWLEDGED));

        mvc.perform(post("/api/v1/incidents/" + ID + "/acknowledge")
                        .header("Authorization", token("incident.read", "incident.acknowledge", "incident.investigate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3,\"note\":\"mine\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.allowedActions[0]").value("START_INVESTIGATION"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> permissions = ArgumentCaptor.forClass(Collection.class);
        verify(commands).acknowledge(eq(ID), eq(3L), eq("mine"), permissions.capture());
        assertThat(permissions.getValue()).contains("incident.acknowledge", "incident.investigate");
    }

    @Test
    void invalidBodies_are400_withFieldErrors() throws Exception {
        String engineer = token("incident.read", "incident.acknowledge", "incident.investigate", "incident.mitigate",
                "incident.resolve");
        mvc.perform(post("/api/v1/incidents/" + ID + "/resolve").header("Authorization", engineer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"rootCause\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("rootCause", "resolution")));
        mvc.perform(post("/api/v1/incidents/" + ID + "/mitigate").header("Authorization", engineer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mitigation\":\"m\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("version")));
        mvc.perform(post("/api/v1/incidents/" + ID + "/acknowledge").header("Authorization", engineer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/incidents?severity=P9").header("Authorization", engineer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(commands);
    }

    @Test
    void domainConflicts_keepTheirSpecificCodes() throws Exception {
        when(commands.close(eq(ID), anyLong(), any(), any())).thenThrow(new ConflictException(
                IncidentErrorCodes.INCIDENT_INVALID_TRANSITION, "Cannot close an incident in status OPEN"));

        mvc.perform(post("/api/v1/incidents/" + ID + "/close").header("Authorization", token("incident.close"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCIDENT_INVALID_TRANSITION"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void list_bindsRepeatedFilters() throws Exception {
        when(queries.list(any(), any())).thenReturn(Page.empty());

        mvc.perform(get("/api/v1/incidents?status=OPEN&status=REOPENED&severity=P1&open=true&unmapped=true&q=odoo"
                        + "&from=2026-09-01T00:00:00Z").header("Authorization", token("incident.read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        ArgumentCaptor<IncidentListQuery> query = ArgumentCaptor.forClass(IncidentListQuery.class);
        verify(queries).list(query.capture(), any(Pageable.class));
        assertThat(query.getValue().statuses()).containsExactly(IncidentStatus.OPEN, IncidentStatus.REOPENED);
        assertThat(query.getValue().severities()).containsExactly(Severity.P1);
        assertThat(query.getValue().open()).isTrue();
        assertThat(query.getValue().unmapped()).isTrue();
        assertThat(query.getValue().from()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    void operationsSummary_needsIncidentRead_andReceivesThePermissions() throws Exception {
        when(summaries.summary(any())).thenReturn(new OperationsSummary(Instant.now(),
                new OperationsSummary.OpenIncidents(0, Map.of(), Map.of(), 0), null, List.of(), null));

        mvc.perform(get("/api/v1/operations/summary").header("Authorization", token("incident.read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts").doesNotExist())
                .andExpect(jsonPath("$.recentAlerts").doesNotExist());
        mvc.perform(get("/api/v1/operations/summary").header("Authorization", token("alert.read")))
                .andExpect(status().isForbidden());
    }
}
