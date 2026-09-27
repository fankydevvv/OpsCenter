package com.opscenter.servicecatalog.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.servicecatalog.application.ServiceCatalogService;
import com.opscenter.servicecatalog.application.ServiceDetail;
import com.opscenter.servicecatalog.application.ServiceListQuery;
import com.opscenter.servicecatalog.application.ServiceQueryService;
import com.opscenter.servicecatalog.domain.ServiceCatalogErrorCodes;
import com.opscenter.servicecatalog.domain.ServiceStatus;
import com.opscenter.shared.application.IdempotentResult;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.support.SecuritySliceConfig;
import com.opscenter.support.TestJwts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of {@code /api/v1/services} and {@code /api/v1/service-environments} (04-API §5,
 * blueprint §6/§7.1) on the real security chain: 401 without a token, 403 per permission code,
 * 400 with field errors for invalid bodies/parameters, 201/200 for create and replay.
 */
@WebMvcTest(controllers = {ServiceController.class, ServiceEnvironmentController.class})
@Import(SecuritySliceConfig.class)
@ActiveProfiles("test")
class ServiceControllerWebMvcTest {

    private static final String VALID_CREATE = "{\"code\":\"payment-api\",\"name\":\"Payment API\"}";

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder jwtEncoder;
    @MockitoBean ServiceCatalogService catalog;
    @MockitoBean ServiceQueryService queries;

    private String admin() {
        return TestJwts.issue(jwtEncoder, UUID.randomUUID(), UUID.randomUUID(), "admin", List.of("ADMIN"),
                List.of("service.read", "service.create", "service.update"), Instant.now(), Duration.ofMinutes(30));
    }

    /** Engineer after V007.1: service.read only. */
    private String engineer() {
        return TestJwts.issue(jwtEncoder, UUID.randomUUID(), UUID.randomUUID(), "engineer.a", List.of("ENGINEER"),
                List.of("organization.read", "team.read", "service.read"), Instant.now(), Duration.ofMinutes(30));
    }

    private static ServiceDetail detail(UUID id) {
        return new ServiceDetail(id, UUID.randomUUID(), "payment-api", "Payment API", null, ServiceStatus.ACTIVE, true,
                null, null, null, null, List.of(), List.of(), Instant.now(), Instant.now(), null, 0);
    }

    @Test
    void withoutToken_everyEndpointIs401() throws Exception {
        mvc.perform(get("/api/v1/services"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        mvc.perform(post("/api/v1/services").contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/service-environments/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(catalog, queries);
    }

    @Test
    void TC_RBAC_003_engineer_canRead_butEveryWriteIs403() throws Exception {
        when(queries.list(any(), any())).thenReturn(Page.empty());
        String engineer = engineer();
        UUID id = UUID.randomUUID();

        mvc.perform(get("/api/v1/services").header("Authorization", "Bearer " + engineer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        // valid bodies on purpose: @PreAuthorize runs after body validation
        mvc.perform(post("/api/v1/services").header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));
        mvc.perform(patch("/api/v1/services/" + id).header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/services/" + id + "/ownership").header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/services/" + id + "/environments").header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"environmentCode\":\"DEV\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/service-environments/" + id).header("Authorization", "Bearer " + engineer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(catalog);
    }

    @Test
    void create_validatesCodeNameEnvironmentAndUrlScheme() throws Exception {
        String body = "{\"code\":\"-bad code\",\"name\":\"\",\"environments\":[{\"environmentCode\":\"x\","
                + "\"dashboardUrl\":\"javascript:alert(1)\"}]}";

        mvc.perform(post("/api/v1/services").header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("code", "name",
                        "environments[0].environmentCode", "environments[0].dashboardUrl")));
        mvc.perform(post("/api/v1/services").header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"ok\",\"name\":\"Ok\",\"status\":\"BROKEN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));
        verify(catalog, never()).create(any(), any());
    }

    @Test
    void create_returns201_andAReplayReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(catalog.create(any(), eq("svc-1")))
                .thenReturn(IdempotentResult.created(detail(id)))
                .thenReturn(IdempotentResult.replayed(detail(id)));

        for (int expected : new int[] {201, 200}) {
            mvc.perform(post("/api/v1/services").header("Authorization", "Bearer " + admin())
                            .header("Idempotency-Key", "svc-1")
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                    .andExpect(status().is(expected))
                    .andExpect(jsonPath("$.id").value(id.toString()))
                    .andExpect(jsonPath("$.code").value("payment-api"));
        }
    }

    @Test
    void patchAndPut_requireVersion_andConflictsAreRendered() throws Exception {
        UUID id = UUID.randomUUID();
        when(catalog.update(eq(id), any())).thenThrow(new ConflictException("CONCURRENCY_VERSION_CONFLICT", "stale"));

        mvc.perform(patch("/api/v1/services/" + id).header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));
        mvc.perform(patch("/api/v1/services/" + id).header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"version\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENCY_VERSION_CONFLICT"));
        mvc.perform(put("/api/v1/services/" + id + "/ownership").header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"additionalOwners\":[{\"teamId\":\"" + UUID.randomUUID() + "\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("additionalOwners[0].ownershipType"));
        verify(catalog, never()).replaceOwnership(any(), any());
    }

    @Test
    void environmentEndpoints_validateUrls_andRenderDomainErrors() throws Exception {
        UUID serviceId = UUID.randomUUID();
        when(catalog.addEnvironment(eq(serviceId), any())).thenThrow(new ConflictException(
                ServiceCatalogErrorCodes.SERVICE_ENVIRONMENT_EXISTS, "exists"));

        mvc.perform(post("/api/v1/services/" + serviceId + "/environments").header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"environmentCode\":\"PROD\",\"healthEndpoint\":\"ftp://x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("healthEndpoint"));
        mvc.perform(post("/api/v1/services/" + serviceId + "/environments").header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"environmentCode\":\"PROD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_ENVIRONMENT_EXISTS"));
        mvc.perform(patch("/api/v1/service-environments/" + UUID.randomUUID()).header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dashboardUrl\":\"data:text/html,x\",\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("dashboardUrl"));
    }

    @Test
    void list_bindsFilters_withActiveDefaultingToTrue_andRejectsUnknownStatus() throws Exception {
        UUID team = UUID.randomUUID();
        when(queries.list(any(), any())).thenReturn(Page.empty());

        mvc.perform(get("/api/v1/services?q=pay&status=DEGRADED&environment=prod&owningTeamId=" + team)
                        .header("Authorization", "Bearer " + engineer()))
                .andExpect(status().isOk());
        verify(queries).list(eq(new ServiceListQuery("pay", ServiceStatus.DEGRADED, team, "prod", true)), any());
        mvc.perform(get("/api/v1/services?status=BROKEN").header("Authorization", "Bearer " + engineer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"));
    }
}
