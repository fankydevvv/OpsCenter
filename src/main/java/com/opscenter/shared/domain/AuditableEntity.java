package com.opscenter.shared.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Base class of every business aggregate: UUID id, audit columns and optimistic-lock version
 * (03-DB §3.1, §3.4, §28).
 * <p>
 * The id is assigned by the application in the constructor (no database sequence), which makes
 * an entity identifiable before it is persisted and keeps {@code equals/hashCode} stable.
 * {@code created_*}/{@code updated_*} are filled by Spring Data JPA auditing
 * ({@code JpaAuditingConfig}), so services never set them by hand; {@code *_by} stays
 * {@code null} for system actions and seed data (D-11).
 * <p>
 * Lombok's {@code @Data} is intentionally not used on entities: an equals/hashCode over all
 * columns breaks Hibernate's identity semantics.
 * <p>
 * {@link Persistable} is implemented because the id is assigned by the application: Spring Data
 * decides between {@code persist()} and {@code merge()} by asking {@link #isNew()}, and without
 * this hint an entity with a non-null id would always be <em>merged</em> - an extra SELECT, a
 * different managed instance handed back from {@code save()}, and a version bumped to 1 on the
 * very first insert. The flag is transient: {@code true} until the row is inserted or loaded.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Required by JPA; subclasses expose a meaningful constructor. */
    protected AuditableEntity() {
    }

    protected AuditableEntity(UUID id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public UUID getId() {
        return id;
    }

    /** {@code true} until the entity has been inserted or loaded; drives persist-vs-merge in Spring Data. */
    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Compares the version sent by the client with the current one and fails with
     * {@code 409 CONCURRENCY_VERSION_CONFLICT} when the client worked on stale data (03-DB §28).
     * Hibernate performs the same check at flush time; doing it up-front gives a clear message.
     */
    public void assertVersion(long expectedVersion) {
        if (this.version != expectedVersion) {
            throw new ConflictException(ErrorCodes.CONCURRENCY_VERSION_CONFLICT,
                    "Resource was modified by someone else (expected version " + expectedVersion
                            + ", current " + this.version + "). Reload and retry.");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return id != null && id.equals(((AuditableEntity) other).id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
