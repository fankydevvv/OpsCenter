package com.opscenter.alert.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.alert.domain.AlertSourceType;
import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.shared.domain.Severity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Folding the events of one delivery per fingerprint (review finding "rule pairs"; D-46, D-47, D-59):
 * firing wins over resolved, the most severe firing event represents the fingerprint, and a
 * fingerprint is only resolved when every one of its events is.
 */
class CollapsedEventTest {

    private static final Instant T0 = Instant.parse("2026-09-27T10:00:00Z");

    private static CanonicalAlertEvent event(int index, String alertName, String instance, AlertStatus status,
                                             Severity severity, Instant endsAt) {
        return new CanonicalAlertEvent(index, UUID.randomUUID(), "alertmanager", AlertSourceType.ALERTMANAGER, status,
                T0, alertName, "odoo-erp", "DEV", instance, severity, false, severity.name(), alertName + " " + index,
                null, null, null, T0, endsAt, Map.of(), Map.of(), null);
    }

    @Test
    void criticalFiring_andWarningResolved_ofTheSameFingerprint_isOneFiringDecision() {
        CanonicalAlertEvent critical = event(0, "Latency", "a:1", AlertStatus.FIRING, Severity.P1, null);
        CanonicalAlertEvent warningResolved = event(1, "Latency", "a:1", AlertStatus.RESOLVED, Severity.P3, T0);

        List<CollapsedEvent> decisions = CollapsedEvent.collapse(List.of(critical, warningResolved));

        assertThat(decisions).hasSize(1);
        assertThat(decisions.getFirst().event()).isSameAs(critical);
        assertThat(decisions.getFirst().indexes()).containsExactly(0, 1);
        assertThat(decisions.getFirst().eventIds()).containsExactly(critical.eventId(), warningResolved.eventId());
    }

    @Test
    void theOrderInTheArrayDoesNotMatter_theMostSevereFiringEventRepresents() {
        CanonicalAlertEvent warningResolved = event(0, "Latency", "a:1", AlertStatus.RESOLVED, Severity.P3, T0);
        CanonicalAlertEvent warningFiring = event(1, "Latency", "a:1", AlertStatus.FIRING, Severity.P3, null);
        CanonicalAlertEvent criticalFiring = event(2, "Latency", "a:1", AlertStatus.FIRING, Severity.P1, null);

        CollapsedEvent decision = CollapsedEvent.collapse(List.of(warningResolved, warningFiring, criticalFiring))
                .getFirst();

        assertThat(decision.event()).isSameAs(criticalFiring);
        assertThat(decision.size()).isEqualTo(3);
    }

    @Test
    void onlyResolvedEvents_areResolved_representedByTheOneThatEndedLast() {
        CanonicalAlertEvent early = event(0, "Latency", "a:1", AlertStatus.RESOLVED, Severity.P1, T0);
        CanonicalAlertEvent late = event(1, "Latency", "a:1", AlertStatus.RESOLVED, Severity.P3, T0.plusSeconds(30));

        CollapsedEvent decision = CollapsedEvent.collapse(List.of(early, late)).getFirst();

        assertThat(decision.event().status()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(decision.event()).isSameAs(late);
    }

    @Test
    void differentFingerprints_stayApart_inTheOrderTheyFirstAppear() {
        CanonicalAlertEvent b1 = event(0, "B", "b:1", AlertStatus.FIRING, Severity.P2, null);
        CanonicalAlertEvent a1 = event(1, "A", "a:1", AlertStatus.FIRING, Severity.P2, null);
        CanonicalAlertEvent b2 = event(2, "B", "b:2", AlertStatus.FIRING, Severity.P2, null);

        List<CollapsedEvent> decisions = CollapsedEvent.collapse(List.of(b1, a1, b2));

        assertThat(decisions).extracting(d -> d.event().index()).containsExactly(0, 1, 2);
        assertThat(decisions).allSatisfy(d -> assertThat(d.size()).isEqualTo(1));
    }
}
