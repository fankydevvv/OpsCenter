package com.opscenter.servicecatalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * An additional owner of a service ({@code service_owners}, 03-DB §7.2, blueprint D-34).
 * <p>
 * Part of the {@link CatalogService} aggregate: rows are created and removed only through
 * {@code CatalogService.replaceAdditionalOwners}, never saved on their own. Target and type never
 * change (a different target is a different row); only the priority of a kept owner can, so the row
 * has only {@code created_*} columns and no version of its own - the aggregate root's version
 * protects the whole ownership (D-35, 03-DB §28). Team and user are referenced by id only (module
 * boundary, no JPA relation into organization/identity).
 */
@Entity
@Table(name = "service_owners")
@EntityListeners(AuditingEntityListener.class)
public class ServiceOwner {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false, updatable = false)
    private CatalogService service;

    @Column(name = "team_id", updatable = false)
    private UUID teamId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ownership_type", nullable = false, updatable = false, length = 50)
    private OwnershipType ownershipType;

    @Column(name = "priority", nullable = false)
    private int priority;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected ServiceOwner() {
    }

    ServiceOwner(CatalogService service, ServiceOwnerSpec spec) {
        this.id = UUID.randomUUID();
        this.service = Objects.requireNonNull(service, "service");
        this.teamId = spec.teamId();
        this.userId = spec.userId();
        this.ownershipType = spec.ownershipType();
        this.priority = spec.priority();
    }

    /** Only the priority of a kept owner can change; target and type are its identity. */
    void changePriority(int newPriority) {
        if (newPriority < 1 || newPriority > 100) {
            throw new IllegalArgumentException("priority must be 1..100");
        }
        this.priority = newPriority;
    }

    /** Same format as {@link ServiceOwnerSpec#targetKey()}: target + ownership type. */
    public String targetKey() {
        return (teamId != null ? "team:" + teamId : "user:" + userId) + "/" + ownershipType;
    }

    public UUID getId() {
        return id;
    }

    public CatalogService getService() {
        return service;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getUserId() {
        return userId;
    }

    public OwnershipType getOwnershipType() {
        return ownershipType;
    }

    public int getPriority() {
        return priority;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof ServiceOwner that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return ServiceOwner.class.hashCode();
    }
}
