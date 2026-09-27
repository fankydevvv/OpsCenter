package com.opscenter.shared.infrastructure.persistence;

import java.time.Instant;
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

import org.springframework.data.domain.Persistable;

/**
 * JPA mapping of {@code idempotency_keys} (03-DB §22).
 * <p>
 * This is a technical record, not a business aggregate, so it does not extend
 * {@code AuditableEntity}: it has no actor, no version and is purged by retention, not soft-deleted.
 * State changes go through the small set of methods below so the status machine
 * ({@code IN_PROGRESS -> COMPLETED | FAILED}) is visible in one place. {@link Persistable} lets
 * the claim be a plain INSERT (the unique constraint is the lock) instead of SELECT + merge.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "integration_id")
    private UUID integrationId;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 128)
    private String requestHash;

    @Column(name = "resource_type", length = 100)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private IdempotencyStatus status;

    @Column(name = "response_code")
    private Integer responseCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected IdempotencyKeyEntity() {
    }

    public IdempotencyKeyEntity(UUID id, UUID integrationId, String idempotencyKey, String requestHash,
                                String resourceType, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.integrationId = integrationId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.resourceType = resourceType;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void complete(UUID resourceId, int responseCode) {
        this.status = IdempotencyStatus.COMPLETED;
        this.resourceId = resourceId;
        this.responseCode = responseCode;
    }

    public void fail(int responseCode) {
        this.status = IdempotencyStatus.FAILED;
        this.responseCode = responseCode;
    }

    /** A FAILED key may be retried: the new attempt takes it over with its own request hash. */
    public void restart(String requestHash, String resourceType, Instant expiresAt) {
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.requestHash = requestHash;
        this.resourceType = resourceType;
        this.resourceId = null;
        this.responseCode = null;
        this.expiresAt = expiresAt;
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

    public UUID getIntegrationId() {
        return integrationId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Integer getResponseCode() {
        return responseCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
