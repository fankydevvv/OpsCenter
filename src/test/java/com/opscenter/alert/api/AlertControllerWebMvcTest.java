package com.opscenter.alert.api;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.alert.application.AlertListQuery;
import com.opscenter.alert.application.AlertQueryService;
import com.opscenter.alert.application.RawPayloadDownload;
import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.servicecatalog.application.MappingStatus;
import com.opscenter.shared.application.storage.ObjectStorageUnavailableException;
import com.opscenter.shared.domain.Severity;
import com.opscenter.support.SecuritySliceConfig;
import com.opscenter.support.TestJwts;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of {@code /api/v1/alerts} (04-API §6, blueprint §6/§7.3): read-only, raw download ADMIN-only. */
@WebMvcTest(controllers = AlertController.class)
@Import(SecuritySliceConfig.class)
@ActiveProfiles("test")
class AlertControllerWebMvcTest {

    private static final UUID ID = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder jwtEncoder;
    @MockitoBean AlertQueryService queries;

    private String token(String... permissions) {
        return "Bearer " + TestJwts.issue(jwtEncoder, UUID.randomUUID(), UUID.randomUUID(), "u", List.of("ENGINEER"),
                List.of(permissions), Instant.now(), Duration.ofMinutes(30));
    }

    @Test
    void readEndpoints_need_alertRead() throws Exception {
        mvc.perform(get("/api/v1/alerts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/alerts").header("Authorization", token("incident.read")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));
        mvc.perform(get("/api/v1/alerts/" + ID).header("Authorization", token("incident.read")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(queries);
    }

    @Test
    void rawDownload_needs_alertRawRead_notJust_alertRead() throws Exception {
        mvc.perform(get("/api/v1/alerts/" + ID + "/raw").header("Authorization", token("alert.read")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(queries);

        byte[] raw = "{\"alerts\":[]}".getBytes(StandardCharsets.UTF_8);
        when(queries.raw(eq(ID), isNull())).thenReturn(new RawPayloadDownload("alert-x.json", "application/json", raw));
        mvc.perform(get("/api/v1/alerts/" + ID + "/raw").header("Authorization", token("alert.raw.read")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("alert-x.json")))
                .andExpect(content().bytes(raw));
    }

    @Test
    void rawDownload_whenStorageIsDown_is503() throws Exception {
        when(queries.raw(eq(ID), any())).thenThrow(new ObjectStorageUnavailableException("MinIO unreachable", null));
        mvc.perform(get("/api/v1/alerts/" + ID + "/raw").header("Authorization", token("alert.raw.read")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OBJECT_STORAGE_UNAVAILABLE"));
    }

    @Test
    void list_bindsFilters_andRejectsUnknownEnumValues() throws Exception {
        when(queries.list(any(), any())).thenReturn(Page.empty());
        mvc.perform(get("/api/v1/alerts?status=FIRING&severity=P1&severity=P2&mappingStatus=UNMAPPED&q=disk")
                        .header("Authorization", token("alert.read")))
                .andExpect(status().isOk());
        ArgumentCaptor<AlertListQuery> query = ArgumentCaptor.forClass(AlertListQuery.class);
        verify(queries).list(query.capture(), any());
        assertThat(query.getValue().statuses()).containsExactly(AlertStatus.FIRING);
        assertThat(query.getValue().severities()).containsExactly(Severity.P1, Severity.P2);
        assertThat(query.getValue().mappingStatus()).isEqualTo(MappingStatus.UNMAPPED);
        assertThat(query.getValue().q()).isEqualTo("disk");

        mvc.perform(get("/api/v1/alerts?mappingStatus=SOMETIMES").header("Authorization", token("alert.read")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
