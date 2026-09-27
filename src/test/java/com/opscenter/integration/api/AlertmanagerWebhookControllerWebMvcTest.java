package com.opscenter.integration.api;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.integration.application.AlertmanagerWebhookService;
import com.opscenter.integration.application.IntegrationSourceRef;
import com.opscenter.integration.application.WebhookAuthenticator;
import com.opscenter.integration.application.WebhookDeliverySummary;
import com.opscenter.integration.domain.IntegrationAuthType;
import com.opscenter.integration.domain.IntegrationRateLimitedException;
import com.opscenter.integration.domain.IntegrationSource;
import com.opscenter.integration.domain.IntegrationSourceType;
import com.opscenter.integration.infrastructure.EnvSecretResolver;
import com.opscenter.integration.infrastructure.IntegrationConfig;
import com.opscenter.integration.infrastructure.IntegrationSourceRepository;
import com.opscenter.integration.infrastructure.security.AuthFailureThrottle;
import com.opscenter.integration.infrastructure.security.IntegrationWebhookSecurityConfig;
import com.opscenter.shared.application.ratelimit.RateLimitDecision;
import com.opscenter.shared.application.ratelimit.RateLimiter;
import com.opscenter.support.SecuritySliceConfig;
import com.opscenter.support.TestJwts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The webhook's own security chain and HTTP contract (blueprint D-39, D-40, D-53; 04-API §6):
 * 401 for a missing/wrong token - also for a valid user JWT -, 503 when the source is not usable,
 * 429 after too many failures, 400 with field errors, 413 above the size cap, 200 otherwise. The
 * real {@link WebhookAuthenticator}, secret resolver, throttle and payload reader run; only the
 * repository, the rate-limit counter and the use case are mocked.
 */
@WebMvcTest(controllers = AlertmanagerWebhookController.class)
@Import({SecuritySliceConfig.class, IntegrationWebhookSecurityConfig.class, IntegrationConfig.class,
        WebhookAuthenticator.class, EnvSecretResolver.class, AuthFailureThrottle.class, AlertmanagerPayloadReader.class})
@ActiveProfiles("test")
class AlertmanagerWebhookControllerWebMvcTest {

    private static final String URL = "/api/v1/integrations/alertmanager/webhook";
    /** application-test.yml */
    private static final String TOKEN = "test-only-alertmanager-token-0123456789abcdef";
    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-4000-8000-000000000301");
    private static final String VALID = """
            {"version":"4","status":"firing","alerts":[{"status":"firing",
             "labels":{"alertname":"TargetDown","service":"odoo-erp","environment":"DEV","instance":"t:9100"},
             "annotations":{"summary":"down"},"startsAt":"2026-09-27T13:00:00Z","endsAt":"0001-01-01T00:00:00Z"}]}
            """;

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired WebhookAuthenticator authenticator;
    @MockitoBean IntegrationSourceRepository sources;
    @MockitoBean RateLimiter rateLimiter;
    @MockitoBean AlertmanagerWebhookService webhooks;

    private final IntegrationSource source = mock(IntegrationSource.class);

    @BeforeEach
    void registeredAndEnabledSource() {
        when(source.getId()).thenReturn(SOURCE_ID);
        when(source.getCode()).thenReturn("alertmanager");
        when(source.getOrganizationId()).thenReturn(UUID.randomUUID());
        when(source.getSourceType()).thenReturn(IntegrationSourceType.ALERTMANAGER);
        when(source.getAuthType()).thenReturn(IntegrationAuthType.TOKEN);
        when(source.getSecretRef()).thenReturn("env:OPSCENTER_ALERTMANAGER_TOKEN");
        when(source.isEnabled()).thenReturn(true);
        when(sources.findAll()).thenReturn(List.of(source));
        // the authenticator keeps an in-memory copy of the registry; start every test from the mocks above
        authenticator.invalidate();
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(
                new RateLimitDecision(true, 1, 20, Duration.ofSeconds(30), false));
        when(webhooks.maxPayloadBytes()).thenReturn(1024 * 1024);
        when(webhooks.receive(any(), any(), any(), any())).thenReturn(new WebhookDeliverySummary(UUID.randomUUID(),
                false, 1, 1, 0, 0, 1, 0, 0, 0, true, List.of()));
    }

    private MockHttpServletRequestBuilder webhook(String body) {
        return post(URL).header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void validDelivery_is200_andTheUseCaseGetsTheExactBytesAndTheDeliveryId() throws Exception {
        mvc.perform(webhook(VALID).header("X-Webhook-Id", "am-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentsCreated").value(1));

        ArgumentCaptor<IntegrationSourceRef> principal = ArgumentCaptor.forClass(IntegrationSourceRef.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(webhooks).admit(principal.capture());
        verify(webhooks).receive(eq(principal.getValue()), any(), body.capture(), eq("am-1"));
        assertThat(principal.getValue().id()).isEqualTo(SOURCE_ID);
        assertThat(new String(body.getValue(), StandardCharsets.UTF_8)).isEqualTo(VALID);
    }

    @Test
    void missingOrWrongToken_is401_andTheUseCaseIsNeverCalled() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.code").value("INTEGRATION_AUTH_FAILED"));
        mvc.perform(post(URL).header("Authorization", "Bearer wrong-" + TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTEGRATION_AUTH_FAILED"));
        mvc.perform(post(URL).header("Authorization", "Basic " + TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(webhooks);
    }

    @Test
    void userJwt_isNotAnIntegrationToken() throws Exception {
        String adminJwt = TestJwts.issue(jwtEncoder, UUID.randomUUID(), UUID.randomUUID(), "admin", List.of("ADMIN"),
                List.of("alert.read", "incident.read"), Instant.now(), Duration.ofMinutes(30));

        mvc.perform(post(URL).header("Authorization", "Bearer " + adminJwt).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTEGRATION_AUTH_FAILED"));
        verifyNoInteractions(webhooks);
    }

    @Test
    void disabledSource_is503_withAGenericMessage() throws Exception {
        when(source.isEnabled()).thenReturn(false);
        mvc.perform(webhook(VALID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTEGRATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message", not(containsString("disabled"))));
        verifyNoInteractions(webhooks);
    }

    @Test
    void unknownSource_is401_exactlyLikeAWrongToken() throws Exception {
        // Review finding: a 503 "not registered" answer let strangers enumerate source codes.
        mvc.perform(post("/api/v1/integrations/nagios/webhook").header("Authorization", "Bearer " + TOKEN)
                        .with(r -> { r.setRemoteAddr("10.9.8.1"); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTEGRATION_AUTH_FAILED"))
                .andExpect(jsonPath("$.message").value("Missing or invalid integration token"));
        verifyNoInteractions(webhooks);
    }

    @Test
    void tooManyFailedAuthentications_answer429_evenWithTheRightTokenAfterwards() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(
                new RateLimitDecision(false, 21, 20, Duration.ofSeconds(30), false));
        // simulate a different client address so the in-memory block does not leak into other tests
        mvc.perform(post(URL).with(r -> { r.setRemoteAddr("10.9.8.7"); return r; })
                        .header("Authorization", "Bearer nope").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("INTEGRATION_RATE_LIMITED"));
        mvc.perform(webhook(VALID).with(r -> { r.setRemoteAddr("10.9.8.7"); return r; }))
                .andExpect(status().isTooManyRequests());
        verifyNoInteractions(webhooks);
    }

    @Test
    void invalidPayloads_are400_withTheOffendingField() throws Exception {
        mvc.perform(webhook(VALID.replace("\"alertname\":\"TargetDown\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("alerts[0].labels.alertname")));
        mvc.perform(webhook(VALID.replace("[{\"status\":\"firing\",", "[{\"status\":\"burning\",")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("alerts[0].status")));
        mvc.perform(webhook(VALID.replace("\"startsAt\":\"2026-09-27T13:00:00Z\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("alerts[0].startsAt")));
        mvc.perform(webhook("{\"version\":\"4\",\"alerts\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("alerts")));
        mvc.perform(webhook("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));
        mvc.perform(webhook(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));
    }

    @Test
    void bodyAboveTheLimit_is413() throws Exception {
        when(webhooks.maxPayloadBytes()).thenReturn(2048);
        mvc.perform(webhook(VALID.replace("\"down\"", "\"" + "x".repeat(4096) + "\"")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void sourceOverItsRate_is429() throws Exception {
        doThrow(new IntegrationRateLimitedException("slow down")).when(webhooks).admit(any());
        mvc.perform(webhook(VALID))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("INTEGRATION_RATE_LIMITED"));
    }

    @Test
    void theJwtChain_isUnaffected_otherIntegrationPathsStillNeedAUserToken() throws Exception {
        mvc.perform(post("/api/v1/integrations/alertmanager/other").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }
}
