package com.opscenter.alert.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.opscenter.alert.domain.AlertStatus;

/**
 * All events of ONE delivery that share an OpsCenter fingerprint, folded into one decision
 * (blueprint D-46, D-47, D-59; review finding "rule pairs").
 * <p>
 * Why this is needed: the fingerprint deliberately ignores every label except alert name, service,
 * environment and instance. Alertmanager, however, identifies alerts by <em>all</em> labels, so two
 * of its alerts can map to the same OpsCenter fingerprint - the classic case is a rule pair
 * {@code HighLatency{severity="warning"}} / {@code HighLatency{severity="critical"}} on the same
 * instance, which always arrive together in one group notification. Processed one by one in array
 * order, {@code [critical firing, warning resolved]} would first count the critical one and then
 * RESOLVE the very alert that is still burning. Folded first, the delivery says what is true about
 * the fingerprint as a whole:
 * <ul>
 *   <li>FIRING if <em>any</em> of its events fires; the representative is the most severe firing
 *       event (earliest position on a tie) - its severity, summary and labels are stored;</li>
 *   <li>RESOLVED only if <em>every</em> event is resolved; the representative is the one that ended
 *       last;</li>
 *   <li>one occurrence row per fingerprint and delivery (two firing duplicates are one
 *       notification of the alert, not two).</li>
 * </ul>
 *
 * @param event    the representative event the ingestion processes
 * @param indexes  positions (in the delivery) of all folded events, ascending
 * @param eventIds their canonical event ids, same order as {@code indexes}
 */
public record CollapsedEvent(CanonicalAlertEvent event, List<Integer> indexes, List<UUID> eventIds) {

    public CollapsedEvent {
        Objects.requireNonNull(event, "event");
        indexes = List.copyOf(indexes);
        eventIds = List.copyOf(eventIds);
    }

    /** Groups by fingerprint, keeping the order in which fingerprints first appear. */
    public static List<CollapsedEvent> collapse(List<CanonicalAlertEvent> events) {
        Map<String, List<CanonicalAlertEvent>> byFingerprint = new LinkedHashMap<>();
        for (CanonicalAlertEvent event : events) {
            byFingerprint.computeIfAbsent(event.fingerprint(), fingerprint -> new ArrayList<>()).add(event);
        }
        return byFingerprint.values().stream().map(CollapsedEvent::of).toList();
    }

    static CollapsedEvent of(List<CanonicalAlertEvent> sameFingerprint) {
        List<CanonicalAlertEvent> ordered = sameFingerprint.stream()
                .sorted(Comparator.comparingInt(CanonicalAlertEvent::index))
                .toList();
        CanonicalAlertEvent representative = ordered.stream()
                .filter(e -> e.status() == AlertStatus.FIRING)
                // Severity declaration order is the ranking: P1 (ordinal 0) is the most severe.
                .min(Comparator.comparing(CanonicalAlertEvent::severity))
                .orElseGet(() -> ordered.stream()
                        .max(Comparator.comparing(CollapsedEvent::endedAt))
                        .orElseThrow());
        return new CollapsedEvent(representative,
                ordered.stream().map(CanonicalAlertEvent::index).toList(),
                ordered.stream().map(CanonicalAlertEvent::eventId).toList());
    }

    /** {@code Stream.min/max} keep the first of equal elements, so ties go to the earliest position. */
    private static Instant endedAt(CanonicalAlertEvent event) {
        return event.endsAt() == null ? Instant.MIN : event.endsAt();
    }

    /** How many events of the delivery this decision covers. */
    public int size() {
        return indexes.size();
    }
}
