package com.opscenter.incident.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * One line of the incident's story ({@code incident_timeline}, 03-DB §13.3): "Alert TargetDown
 * linked", "OPEN -> ACKNOWLEDGED", "All alerts resolved at source" ...
 * <p>
 * Append-only like the status history (03-DB §29). The difference between the two tables: the
 * history is the formal record of status changes (used for MTTA/MTTR later), the timeline is
 * everything a responder wants to read in order, including events that do not change the status.
 * {@code metadata} is JSONB stored as a JSON string (base convention D-12).
 */
@Entity
@Table(name = "incident_timeline")
public class IncidentTimelineEntry implements Persistable<UUID> {

    public static final int TITLE_MAX = 500;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 50)
    private TimelineSource source;

    @Column(name = "title", nullable = false, updatable = false, length = TITLE_MAX)
    private String title;

    @Column(name = "description", updatable = false)
    private String description;

    @Column(name = "event_at", nullable = false, updatable = false)
    private Instant eventAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", updatable = false)
    private String metadata;

    protected IncidentTimelineEntry() {
    }

    public IncidentTimelineEntry(UUID incidentId, String eventType, TimelineSource source, UUID actorId,
                                 String title, String description, Instant eventAt, String metadataJson) {
        this.id = UUID.randomUUID();
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.source = Objects.requireNonNull(source, "source");
        this.actorId = actorId;
        String text = Objects.requireNonNull(title, "title");
        this.title = text.length() <= TITLE_MAX ? text : text.substring(0, TITLE_MAX);
        this.description = description;
        this.eventAt = Objects.requireNonNull(eventAt, "eventAt");
        this.metadata = metadataJson;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public TimelineSource getSource() {
        return source;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Instant getEventAt() {
        return eventAt;
    }

    public String getMetadata() {
        return metadata;
    }
}
