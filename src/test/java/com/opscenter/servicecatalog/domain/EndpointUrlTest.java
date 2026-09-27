package com.opscenter.servicecatalog.domain;

import com.opscenter.shared.domain.InvalidRequestException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Blueprint §7.1: only absolute http(s) URLs reach the UI as links (no {@code javascript:} XSS). */
class EndpointUrlTest {

    @Test
    void acceptsHttpAndHttps_andBlankMeansNone() {
        assertThat(EndpointUrl.check("dashboardUrl", " http://localhost:3100/d/abc ")).isEqualTo("http://localhost:3100/d/abc");
        assertThat(EndpointUrl.check("healthEndpoint", "HTTPS://api.example.com/health")).isEqualTo("HTTPS://api.example.com/health");
        assertThat(EndpointUrl.check("metricEndpoint", "")).isNull();
        assertThat(EndpointUrl.check("metricEndpoint", null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "data:text/html,<script>", "ftp://files.example.com", "/relative/path",
            "http://"})
    void rejectsOtherSchemesAndRelativeUrls(String url) {
        assertThatThrownBy(() -> EndpointUrl.check("dashboardUrl", url))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("dashboardUrl");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://admin:S3cret@odoo:8069/health", "https://token@grafana.example.com/d/x",
            "http://user:@host/metrics"})
    void rejectsEmbeddedCredentials(String url) {
        // review finding: the URL is stored in plain text and shown to every service.read holder
        assertThatThrownBy(() -> EndpointUrl.check("healthEndpoint", url))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void rejectsUrlsLongerThan500() {
        assertThatThrownBy(() -> EndpointUrl.check("healthEndpoint", "http://h/" + "x".repeat(500)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("500");
    }
}
