package com.opscenter.integration.application.alertmanager;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.alert.application.AlertProperties;
import com.opscenter.alert.application.CanonicalAlertEvent;
import com.opscenter.alert.application.SeverityMapping;
import com.opscenter.alert.domain.AlertSourceType;
import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.integration.application.IntegrationSourceRef;
import com.opscenter.integration.domain.IntegrationSourceType;
import com.opscenter.servicecatalog.application.ServiceCatalogProperties;
import com.opscenter.servicecatalog.application.ServiceLookupService;
import com.opscenter.shared.domain.Severity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-ALT-02 normalisation (blueprint §8.1, D-44, D-45): the Alertmanager v4 payload becomes canonical
 * events. The catalog's real label normaliser is used ({@code ServiceLookupService.keyOf} touches no
 * repository), so this test also proves the fingerprint and the service lookup see the same values.
 */
class AlertmanagerEventMapperTest {

    private static final IntegrationSourceRef SOURCE = new IntegrationSourceRef(UUID.randomUUID(), "alertmanager",
            UUID.randomUUID(), IntegrationSourceType.ALERTMANAGER);
    private static final Instant STARTS = Instant.parse("2026-09-27T13:00:00Z");
    private static final Instant ZERO_TIME = Instant.parse("0001-01-01T00:00:00Z");

    private final AlertmanagerEventMapper mapper = new AlertmanagerEventMapper(
            new ServiceLookupService(null, null, null, new ServiceCatalogProperties(
                    new ServiceCatalogProperties.Resolution(List.of("service", "service_name", "app"),
                            List.of("environment", "env"), true, Duration.ofMinutes(10), Duration.ofSeconds(30)))),
            new SeverityMapping(new AlertProperties(null, Severity.P3, Duration.ofHours(24),
                    new AlertProperties.Lock(Duration.ofSeconds(15), Duration.ofSeconds(5)))));

    private static AlertmanagerAlert alert(String status, Map<String, String> labels, Map<String, String> annotations,
                                           Instant endsAt) {
        return new AlertmanagerAlert(status, labels, annotations, STARTS, endsAt, "http://prometheus/graph", "5ef77f1f");
    }

    private static CanonicalAlertEvent single(AlertmanagerEventMapper mapper, AlertmanagerAlert alert) {
        AlertmanagerWebhook payload = new AlertmanagerWebhook("4", "g", 0, alert.status(), "opscenter", Map.of(),
                Map.of(), Map.of(), "http://am", List.of(alert));
        return mapper.map(payload, SOURCE, "req-7", "sha256:abc").getFirst();
    }

    @Test
    void firingAlert_isNormalisedIntoACanonicalEvent() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("alertname", " TargetDown ");
        labels.put("service", "Odoo-ERP");
        labels.put("env", "prod");
        labels.put("instance", "demo-target:9100");
        labels.put("severity", "Critical");
        CanonicalAlertEvent event = single(mapper, alert("firing", labels,
                Map.of("summary", "odoo-erp is down", "description", "details"), ZERO_TIME));

        assertThat(event.alertName()).isEqualTo("TargetDown");
        assertThat(event.serviceCode()).isEqualTo("odoo-erp");
        assertThat(event.environment()).isEqualTo("PRODUCTION");
        assertThat(event.instance()).isEqualTo("demo-target:9100");
        assertThat(event.severity()).isEqualTo(Severity.P1);
        assertThat(event.severityDefaulted()).isFalse();
        assertThat(event.status()).isEqualTo(AlertStatus.FIRING);
        assertThat(event.eventType()).isEqualTo("ALERT_FIRING");
        assertThat(event.sourceType()).isEqualTo(AlertSourceType.ALERTMANAGER);
        assertThat(event.sourceCode()).isEqualTo("alertmanager");
        assertThat(event.summary()).isEqualTo("odoo-erp is down");
        assertThat(event.description()).isEqualTo("details");
        assertThat(event.externalAlertId()).isEqualTo("5ef77f1f");
        assertThat(event.startsAt()).isEqualTo(STARTS);
        assertThat(event.occurredAt()).isEqualTo(STARTS);
        assertThat(event.endsAt()).as("0001-01-01 is Alertmanager's 'no end'").isNull();
        assertThat(event.traceId()).isEqualTo("req-7");
    }

    @Test
    void severityLabels_mapToTheOneScale_andUnknownOrMissingDefaultsToP3() {
        Map<String, Severity> expected = Map.of("critical", Severity.P1, "HIGH", Severity.P2, "error", Severity.P2,
                "major", Severity.P2, "warning", Severity.P3, "minor", Severity.P3, "info", Severity.P4,
                "low", Severity.P4, "none", Severity.P4, "p2", Severity.P2);
        expected.forEach((label, severity) -> {
            CanonicalAlertEvent event = single(mapper, alert("firing", Map.of("alertname", "A", "severity", label),
                    Map.of(), null));
            assertThat(event.severity()).as(label).isEqualTo(severity);
            assertThat(event.severityDefaulted()).as(label).isFalse();
        });

        CanonicalAlertEvent unknown = single(mapper, alert("firing", Map.of("alertname", "A", "severity", "page"),
                Map.of(), null));
        assertThat(unknown.severity()).isEqualTo(Severity.P3);
        assertThat(unknown.severityDefaulted()).isTrue();
        assertThat(unknown.rawSeverity()).isEqualTo("page");
        CanonicalAlertEvent missing = single(mapper, alert("firing", Map.of("alertname", "A"), Map.of(), null));
        assertThat(missing.severity()).isEqualTo(Severity.P3);
        assertThat(missing.severityDefaulted()).isTrue();
        assertThat(missing.rawSeverity()).isNull();
    }

    @Test
    void summaryFallsBack_toDescription_thenToTheAlertName() {
        assertThat(single(mapper, alert("firing", Map.of("alertname", "A"), Map.of("description", "desc"), null))
                .summary()).isEqualTo("desc");
        assertThat(single(mapper, alert("firing", Map.of("alertname", "A"), Map.of("summary", " "), null))
                .summary()).isEqualTo("A");
    }

    @Test
    void serviceLabel_fallsBackTo_serviceName_thenApp() {
        assertThat(single(mapper, alert("firing", Map.of("alertname", "A", "app", "Billing"), Map.of(), null))
                .serviceCode()).isEqualTo("billing");
        assertThat(single(mapper, alert("firing", Map.of("alertname", "A", "service_name", "pay", "app", "x"), Map.of(),
                null)).serviceCode()).isEqualTo("pay");
        CanonicalAlertEvent none = single(mapper, alert("firing", Map.of("alertname", "A"), Map.of(), null));
        assertThat(none.serviceCode()).isNull();
        assertThat(none.environment()).isNull();
    }

    @Test
    void sensitiveLabelAndAnnotationValues_areMasked() {
        CanonicalAlertEvent event = single(mapper, alert("firing",
                Map.of("alertname", "A", "db_password", "hunter2", "API_KEY", "sk-1", "instance", "x:1"),
                Map.of("authorization_header", "Bearer abc", "summary", "ok"), null));

        assertThat(event.labels()).containsEntry("db_password", "***").containsEntry("API_KEY", "***")
                .containsEntry("instance", "x:1");
        assertThat(event.annotations()).containsEntry("authorization_header", "***").containsEntry("summary", "ok");
    }

    @Test
    void resolvedAlert_occurredAtItsEnd() {
        Instant endsAt = STARTS.plusSeconds(300);
        CanonicalAlertEvent event = single(mapper, alert("resolved", Map.of("alertname", "A"), Map.of(), endsAt));

        assertThat(event.status()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(event.eventType()).isEqualTo("ALERT_RESOLVED");
        assertThat(event.endsAt()).isEqualTo(endsAt);
        assertThat(event.occurredAt()).isEqualTo(endsAt);
    }

    @Test
    void everyAlertOfTheDelivery_keepsItsIndex() {
        AlertmanagerWebhook payload = new AlertmanagerWebhook("4", "g", 0, "firing", "r", Map.of(), Map.of(), Map.of(),
                "u", List.of(alert("firing", Map.of("alertname", "A"), Map.of(), null),
                        alert("resolved", Map.of("alertname", "B"), Map.of(), null)));

        List<CanonicalAlertEvent> events = mapper.map(payload, SOURCE, null, "am-1");

        assertThat(events).extracting(CanonicalAlertEvent::index).containsExactly(0, 1);
        assertThat(events).extracting(CanonicalAlertEvent::alertName).containsExactly("A", "B");
        assertThat(events.get(0).eventId()).isNotEqualTo(events.get(1).eventId());
    }

    @Test
    void eventIds_areDeterministicPerSourceDeliveryAndPosition() {
        // 04-API §23.1: "eventId + sourceCode must support idempotency" - a retry maps to the same ids.
        AlertmanagerWebhook payload = new AlertmanagerWebhook("4", "g", 0, "firing", "r", Map.of(), Map.of(), Map.of(),
                "u", List.of(alert("firing", Map.of("alertname", "A"), Map.of(), null)));

        CanonicalAlertEvent first = mapper.map(payload, SOURCE, "req-1", "am-1").getFirst();
        CanonicalAlertEvent retry = mapper.map(payload, SOURCE, "req-2", "am-1").getFirst();
        CanonicalAlertEvent otherDelivery = mapper.map(payload, SOURCE, "req-3", "am-2").getFirst();

        assertThat(retry.eventId()).isEqualTo(first.eventId());
        assertThat(otherDelivery.eventId()).isNotEqualTo(first.eventId());
        assertThat(AlertmanagerEventMapper.eventId("a", "bc", 0)).isNotEqualTo(AlertmanagerEventMapper.eventId("ab", "c", 0));
    }
}
