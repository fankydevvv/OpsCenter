package com.opscenter.shared.infrastructure.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.opscenter.shared.application.status.ComponentState;
import com.opscenter.shared.application.status.ProbeResult;
import com.opscenter.shared.application.status.WebhookActivityQuery;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prometheus and Alertmanager probes (D-63, R-32) against an in-process HTTP stub that serves the
 * real API shapes ({@code /api/v1/status/buildinfo}, {@code /api/v1/targets}, {@code /api/v2/status}).
 */
class HttpProbesTest {

    private HttpServer server;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private String baseUrl;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = responses.get(exchange.getRequestURI().toString());
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private static SystemProbeProperties properties(String prometheus, String alertmanager) {
        return new SystemProbeProperties(Duration.ofSeconds(2), prometheus, alertmanager);
    }

    private static HttpJsonProbeClient client(SystemProbeProperties properties) {
        return new HttpJsonProbeClient(JsonMapper.builder().build(), properties);
    }

    @Test
    void prometheus_reportsVersionAndTargetHealth() throws Exception {
        responses.put("/api/v1/status/buildinfo", "{\"status\":\"success\",\"data\":{\"version\":\"3.15.0\"}}");
        responses.put("/api/v1/targets?state=active", "{\"status\":\"success\",\"data\":{\"activeTargets\":["
                + "{\"health\":\"up\"},{\"health\":\"up\"},{\"health\":\"down\"},{\"health\":\"unknown\"}]}}");
        SystemProbeProperties properties = properties(baseUrl, null);

        ProbeResult result = new PrometheusProbe(client(properties), properties).probe();

        assertThat(result.state()).isEqualTo(ComponentState.UP);
        assertThat(result.version()).isEqualTo("3.15.0");
        assertThat(result.details()).containsEntry("targetsUp", 2).containsEntry("targetsDown", 2);
    }

    @Test
    void alertmanager_reportsVersionCluster_andLastWebhookWhenTheIntegrationModuleKnowsIt() throws Exception {
        responses.put("/api/v2/status", "{\"cluster\":{\"status\":\"ready\"},\"versionInfo\":{\"version\":\"0.34.1\"}}");
        SystemProbeProperties properties = properties(null, baseUrl);
        Instant lastEvent = Instant.parse("2026-09-27T09:59:00Z");
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("activity", (WebhookActivityQuery) source -> "alertmanager".equals(source)
                ? Optional.of(lastEvent) : Optional.empty());
        ObjectProvider<WebhookActivityQuery> activity = beans.getBeanProvider(WebhookActivityQuery.class);

        ProbeResult result = new AlertmanagerProbe(client(properties), properties, activity).probe();

        assertThat(result.state()).isEqualTo(ComponentState.UP);
        assertThat(result.version()).isEqualTo("0.34.1");
        assertThat(result.details()).containsEntry("cluster", "ready").containsEntry("lastWebhookAt", lastEvent);
    }

    @Test
    void notConfigured_isUnknown_andHttpErrorsPropagate() throws Exception {
        SystemProbeProperties blank = properties(" ", "");
        ObjectProvider<WebhookActivityQuery> none = new StaticListableBeanFactory().getBeanProvider(WebhookActivityQuery.class);

        assertThat(new PrometheusProbe(client(blank), blank).probe().state()).isEqualTo(ComponentState.UNKNOWN);
        assertThat(new AlertmanagerProbe(client(blank), blank, none).probe().state()).isEqualTo(ComponentState.UNKNOWN);

        SystemProbeProperties stub = properties(baseUrl, baseUrl);   // stub answers 404 for unknown paths
        assertThatThrownBy(() -> new PrometheusProbe(client(stub), stub).probe())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 404");
    }
}
