package com.opscenter.integration.application.alertmanager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.alert.application.CanonicalAlertEvent;
import com.opscenter.alert.application.SeverityMapping;
import com.opscenter.alert.domain.AlertSourceType;
import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.integration.application.IntegrationSourceRef;
import com.opscenter.integration.domain.SensitiveLabelMasker;
import com.opscenter.servicecatalog.application.ServiceKey;
import com.opscenter.servicecatalog.application.ServiceLookup;

import org.springframework.stereotype.Component;

/**
 * The Alertmanager adapter (02-SAD §28.2): translates the Alertmanager v4 payload into canonical
 * alert events (FR-ALT-02, 04-API §23.1, blueprint §8.1, D-44, D-45).
 * <p>
 * Rules per element of {@code alerts[]}:
 * <ul>
 *   <li>{@code alertName} = label {@code alertname} (validated before);</li>
 *   <li>{@code serviceCode} / {@code environment} = {@link ServiceLookup#keyOf} - first non-blank of
 *       {@code service|service_name|app} and {@code environment|env}, normalised ({@code prod ->
 *       PRODUCTION}). Using the catalog's own normaliser guarantees the fingerprint and the service
 *       lookup agree;</li>
 *   <li>{@code instance} = label {@code instance};</li>
 *   <li>{@code severity} = label {@code severity} through the configurable map (D-45);</li>
 *   <li>{@code summary} = annotation {@code summary}, else {@code description}, else the alert name;</li>
 *   <li>{@code endsAt} before 1970 (Alertmanager's "zero time" {@code 0001-01-01}) = no end;</li>
 *   <li>label/annotation values with credential-like names are masked before anything is stored;</li>
 *   <li>{@code eventId} is <em>deterministic</em>: a name-based UUID of (source code, delivery key,
 *       position). 04-API §23.1 requires "eventId + sourceCode must support idempotency" - a retried
 *       delivery (same idempotency key) therefore produces the same event ids as the first attempt.</li>
 * </ul>
 * No database access and no side effects: the unit test feeds a payload and checks the events.
 */
@Component
public class AlertmanagerEventMapper {

    private final ServiceLookup serviceLookup;
    private final SeverityMapping severities;

    public AlertmanagerEventMapper(ServiceLookup serviceLookup, SeverityMapping severities) {
        this.serviceLookup = serviceLookup;
        this.severities = severities;
    }

    /**
     * @param traceId     the request id of the delivery, copied into every event (04-API §23.1)
     * @param deliveryKey the delivery's idempotency key ({@code Idempotency-Key} / {@code X-Webhook-Id}
     *                    header or {@code sha256:<body hash>}) - the basis of the deterministic event ids
     */
    public List<CanonicalAlertEvent> map(AlertmanagerWebhook payload, IntegrationSourceRef source, String traceId,
                                         String deliveryKey) {
        List<CanonicalAlertEvent> events = new ArrayList<>(payload.alerts().size());
        for (int index = 0; index < payload.alerts().size(); index++) {
            events.add(map(index, payload.alerts().get(index), source, traceId, deliveryKey));
        }
        return events;
    }

    /** Same (source, delivery, position) -> same id; U+001F keeps ("a","bc") and ("ab","c") apart. */
    static UUID eventId(String sourceCode, String deliveryKey, int index) {
        String name = sourceCode + '\u001F' + (deliveryKey == null ? "" : deliveryKey) + '\u001F' + index;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private CanonicalAlertEvent map(int index, AlertmanagerAlert alert, IntegrationSourceRef source, String traceId,
                                    String deliveryKey) {
        Map<String, String> labels = alert.labels() == null ? Map.of() : alert.labels();
        Map<String, String> annotations = alert.annotations() == null ? Map.of() : alert.annotations();

        String alertName = labels.get("alertname").strip();
        ServiceKey key = serviceLookup.keyOf(labels);
        SeverityMapping.Decision severity = severities.map(labels.get("severity"));
        AlertStatus status = "resolved".equals(alert.status()) ? AlertStatus.RESOLVED : AlertStatus.FIRING;
        Instant startsAt = alert.startsAt();
        Instant endsAt = realTime(alert.endsAt());
        Instant occurredAt = status == AlertStatus.RESOLVED && endsAt != null ? endsAt : startsAt;

        return new CanonicalAlertEvent(index, eventId(source.code(), deliveryKey, index), source.code(),
                AlertSourceType.ALERTMANAGER, status,
                occurredAt, alertName, key.serviceCode(), key.environment(), blankToNull(labels.get("instance")),
                severity.severity(), severity.defaulted(), severity.raw(),
                firstNonBlank(annotations.get("summary"), annotations.get("description"), alertName),
                blankToNull(annotations.get("description")), blankToNull(alert.fingerprint()),
                blankToNull(alert.generatorURL()), startsAt, endsAt,
                SensitiveLabelMasker.mask(labels), SensitiveLabelMasker.mask(annotations), traceId);
    }

    /** Alertmanager writes {@code 0001-01-01T00:00:00Z} for "no end time". */
    static Instant realTime(Instant value) {
        return value == null || value.isBefore(Instant.EPOCH) ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
