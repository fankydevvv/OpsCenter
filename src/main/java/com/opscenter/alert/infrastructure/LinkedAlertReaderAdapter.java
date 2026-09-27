package com.opscenter.alert.infrastructure;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.opscenter.alert.domain.Alert;
import com.opscenter.incident.application.LinkedAlertReader;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The alert module's implementation of the incident module's {@link LinkedAlertReader} port
 * (dependency inversion, blueprint §4.1): the incident module says what it needs, this adapter
 * answers from the {@code alerts} table in one batch query.
 */
@Component
public class LinkedAlertReaderAdapter implements LinkedAlertReader {

    private final AlertRepository alerts;

    public LinkedAlertReaderAdapter(AlertRepository alerts) {
        this.alerts = alerts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, LinkedAlertFacts> findAlerts(Collection<UUID> alertIds) {
        if (alertIds == null || alertIds.isEmpty()) {
            return Map.of();
        }
        return alerts.findAllById(alertIds.stream().distinct().toList()).stream()
                .map(LinkedAlertReaderAdapter::facts)
                .collect(Collectors.toMap(LinkedAlertFacts::id, Function.identity()));
    }

    private static LinkedAlertFacts facts(Alert alert) {
        return new LinkedAlertFacts(alert.getId(), alert.getAlertName(), alert.getSeverity(), alert.getStatus().name(),
                alert.getInstance(), alert.getOccurrenceCount(), alert.getLastSeenAt());
    }
}
