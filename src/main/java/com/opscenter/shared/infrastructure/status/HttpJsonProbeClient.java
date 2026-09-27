package com.opscenter.shared.infrastructure.status;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal "GET a JSON document with a short timeout" client for the Prometheus and Alertmanager
 * probes. The JDK {@link HttpClient} is enough here and keeps the probes independent of any web
 * client auto-configuration; the body is parsed with the application's Jackson 3 {@link JsonMapper}.
 */
@Component
public class HttpJsonProbeClient {

    private final JsonMapper jsonMapper;
    private final Duration timeout;
    private final HttpClient http;

    public HttpJsonProbeClient(JsonMapper jsonMapper, SystemProbeProperties properties) {
        this.jsonMapper = jsonMapper;
        this.timeout = properties.timeout();
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @param baseUrl e.g. {@code http://prometheus:9090} (trailing slash tolerated)
     * @param path    e.g. {@code /api/v1/status/buildinfo}
     * @throws IOException on connection problems or a non-2xx answer
     */
    public JsonNode getJson(String baseUrl, String path) throws IOException, InterruptedException {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + " from " + path);
        }
        return jsonMapper.readTree(response.body());
    }
}
