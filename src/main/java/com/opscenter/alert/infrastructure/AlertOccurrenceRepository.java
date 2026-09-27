package com.opscenter.alert.infrastructure;

import java.util.List;
import java.util.UUID;

import com.opscenter.alert.domain.AlertOccurrence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code alert_occurrences} (append-only, 03-DB §11.2). */
public interface AlertOccurrenceRepository extends JpaRepository<AlertOccurrence, UUID> {

    /** All occurrences written by one webhook delivery - used to rebuild a replayed answer (D-43). */
    List<AlertOccurrence> findBySourceEventId(String sourceEventId);

    /** The newest 50 occurrences of an alert (alert detail page). */
    List<AlertOccurrence> findTop50ByAlertIdOrderByObservedAtDesc(UUID alertId);

    long countByAlertId(UUID alertId);
}
