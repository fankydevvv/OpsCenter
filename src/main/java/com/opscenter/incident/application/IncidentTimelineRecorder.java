package com.opscenter.incident.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.incident.domain.IncidentStatusHistory;
import com.opscenter.incident.domain.IncidentTimelineEntry;
import com.opscenter.incident.domain.TimelineSource;
import com.opscenter.incident.infrastructure.IncidentStatusHistoryRepository;
import com.opscenter.incident.infrastructure.IncidentTimelineRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the two append-only journals of an incident: {@code incident_status_history} and
 * {@code incident_timeline} (03-DB §13.2, §13.3).
 * <p>
 * {@code Propagation.MANDATORY} for the same reason as the audit recorder and the outbox: a history
 * line only makes sense in the transaction that changed the status (03-DB §27) - calling it outside
 * one is a bug and fails immediately.
 * <p>
 * Several entries written by one operation (e.g. "incident created" + "service unresolved") get
 * timestamps one microsecond apart ({@link #next}), so reading the timeline ordered by
 * {@code event_at} always returns them in the order they were written. PostgreSQL stores
 * microseconds, which is why "now" is truncated to that precision first ({@link #start}).
 */
@Component
public class IncidentTimelineRecorder {

    private final IncidentTimelineRepository timeline;
    private final IncidentStatusHistoryRepository history;
    private final JsonMapper jsonMapper;

    public IncidentTimelineRecorder(IncidentTimelineRepository timeline, IncidentStatusHistoryRepository history,
                                    JsonMapper jsonMapper) {
        this.timeline = timeline;
        this.history = history;
        this.jsonMapper = jsonMapper;
    }

    /** "Now" at the precision PostgreSQL keeps, the first timestamp of an operation. */
    public static Instant start(Instant now) {
        return now.truncatedTo(ChronoUnit.MICROS);
    }

    /** The timestamp of the next entry of the same operation. */
    public static Instant next(Instant previous) {
        return previous.plus(1, ChronoUnit.MICROS);
    }

    /**
     * @param actorId  the person, or {@code null} for system/source events
     * @param metadata small machine-readable details (ids, codes); never free text from a client
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IncidentTimelineEntry timeline(UUID incidentId, String eventType, TimelineSource source, UUID actorId,
                                          String title, String description, Instant at, Map<String, ?> metadata) {
        String json = metadata == null || metadata.isEmpty() ? null : jsonMapper.writeValueAsString(metadata);
        return timeline.save(new IncidentTimelineEntry(incidentId, eventType, source, actorId, title, description, at,
                json));
    }

    /** @param changedBy {@code null} for a system transition */
    @Transactional(propagation = Propagation.MANDATORY)
    public IncidentStatusHistory statusChange(UUID incidentId, IncidentStatus from, IncidentStatus to, UUID changedBy,
                                              Instant at, String reason) {
        return history.save(new IncidentStatusHistory(incidentId, from, to, changedBy, at, reason));
    }
}
