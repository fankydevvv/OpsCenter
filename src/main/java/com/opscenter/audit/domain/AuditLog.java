package com.opscenter.audit.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * One line of the append-only audit trail ({@code audit_logs}, 03-DB §21).
 * <p>
 * Deliberately <b>not</b> an {@code AuditableEntity}: an audit record is immutable, has no
 * {@code updated_*} columns, no version and no setters - the only way to change history would be
 * a database migration, which is the intent of 03-DB §29 "append-only".
 * JSONB snapshots are stored as JSON strings ({@code @JdbcTypeCode(SqlTypes.JSON)}, D-12).
 * Implements {@link Persistable} for the same reason as {@code AuditableEntity}: the id is
 * assigned here, so without the hint every insert would first SELECT to decide persist-vs-merge.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "action", nullable = false, updatable = false, length = 100)
    private String action;

    @Column(name = "resource_type", nullable = false, updatable = false, length = 100)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_data", updatable = false)
    private String beforeData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_data", updatable = false)
    private String afterData;

    @Column(name = "request_id", updatable = false, length = 100)
    private String requestId;

    @Column(name = "source_ip", updatable = false, length = 64)
    private String sourceIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    public AuditLog(UUID id, UUID organizationId, UUID actorId, String action, String resourceType,
                    UUID resourceId, String beforeData, String afterData, String requestId, String sourceIp,
                    Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.actorId = actorId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.beforeData = beforeData;
        this.afterData = afterData;
        this.requestId = requestId;
        this.sourceIp = sourceIp;
        this.createdAt = createdAt;
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

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public String getBeforeData() {
        return beforeData;
    }

    public String getAfterData() {
        return afterData;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
