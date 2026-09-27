package com.opscenter.alert.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.opscenter.alert.domain.AlertSourceType;
import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.shared.application.storage.ContentHashes;
import com.opscenter.shared.domain.Severity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-ALT-03 / FR-ALT-04 (blueprint §8.2, D-46): what the fingerprint and the correlation key depend on
 * - and, just as important, what they must NOT depend on.
 */
class FingerprintsTest {

    private static CanonicalAlertEvent event(String instance, Instant startsAt, String summary, Map<String, String> labels) {
        return new CanonicalAlertEvent(0, UUID.randomUUID(), "alertmanager", AlertSourceType.ALERTMANAGER,
                AlertStatus.FIRING, startsAt, "TargetDown", "odoo-erp", "DEV", instance, Severity.P1, false,
                "critical", summary, null, "am-fp", null, startsAt, null, labels, Map.of(), "req-1");
    }

    @Test
    void fingerprint_isSha256OfTheFourParts_joinedWithUnitSeparator() {
        String expected = ContentHashes.sha256Hex("TargetDown\u001Fodoo-erp\u001FDEV\u001Fdemo-target:9100"
                .getBytes(StandardCharsets.UTF_8));
        assertThat(Fingerprints.alert("TargetDown", "odoo-erp", "DEV", "demo-target:9100")).isEqualTo(expected)
                .hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void timestampsAnnotationsAndExtraLabels_doNotChangeTheFingerprint() {
        CanonicalAlertEvent first = event("a:1", Instant.parse("2026-09-27T10:00:00Z"), "down",
                Map.of("job", "x", "pod", "p-1"));
        CanonicalAlertEvent later = event("a:1", Instant.parse("2026-09-27T11:30:00Z"), "still down",
                Map.of("job", "y", "pod", "p-2", "team", "ops"));

        assertThat(later.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(later.correlationKey()).isEqualTo(first.correlationKey());
    }

    @Test
    void anotherInstance_changesTheFingerprint_butNotTheCorrelationKey() {
        CanonicalAlertEvent one = event("node-1:9100", Instant.EPOCH, null, Map.of());
        CanonicalAlertEvent two = event("node-2:9100", Instant.EPOCH, null, Map.of());

        assertThat(two.fingerprint()).isNotEqualTo(one.fingerprint());
        assertThat(two.correlationKey()).isEqualTo(one.correlationKey());
    }

    @Test
    void theSeparator_preventsAmbiguousConcatenations() {
        assertThat(Fingerprints.alert("a|b", "c", "D", null)).isNotEqualTo(Fingerprints.alert("a", "b|c", "D", null));
        assertThat(Fingerprints.alert("ab", "c", "DEV", "")).isNotEqualTo(Fingerprints.alert("a", "bc", "DEV", ""));
    }

    @Test
    void missingParts_countAsEmpty_andWhitespaceIsIgnored() {
        assertThat(Fingerprints.alert("X", null, null, null)).isEqualTo(Fingerprints.alert(" X ", "", "", ""));
        assertThat(Fingerprints.correlation("X", "svc", "DEV")).isNotEqualTo(Fingerprints.alert("X", "svc", "DEV", ""));
    }
}
