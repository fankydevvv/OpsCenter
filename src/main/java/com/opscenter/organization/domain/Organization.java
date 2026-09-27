package com.opscenter.organization.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.opscenter.shared.domain.AuditableEntity;

/**
 * Tenant-like root of all teams ({@code organizations}, 03-DB §6.1).
 * <p>
 * The thesis runs a single organization ({@link #DEFAULT_CODE}, seeded by V005), but the column
 * exists on teams from day one so a multi-tenant upgrade only adds filters instead of a schema
 * migration. Soft delete follows 03-DB §3.3 (D-10).
 */
@Entity
@Table(name = "organizations")
public class Organization extends AuditableEntity {

    /** Code of the organization the base seeds and uses when a team omits {@code organizationId}. */
    public static final String DEFAULT_CODE = "DEFAULT";

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MasterDataStatus status;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Organization() {
    }

    public Organization(UUID id, String code, String name) {
        super(id);
        this.code = Objects.requireNonNull(code, "code");
        this.name = Objects.requireNonNull(name, "name");
        this.status = MasterDataStatus.ACTIVE;
        this.active = true;
    }

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "name").trim();
    }

    public void changeStatus(MasterDataStatus newStatus, Instant now) {
        if (newStatus == this.status) {
            return;
        }
        this.status = newStatus;
        this.active = newStatus == MasterDataStatus.ACTIVE;
        this.deletedAt = active ? null : now;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public MasterDataStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
