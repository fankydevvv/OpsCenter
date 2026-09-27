package com.opscenter.identity.domain;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import com.opscenter.shared.domain.AuditableEntity;

import org.hibernate.annotations.BatchSize;

/**
 * An RBAC role ({@code roles} + {@code role_permissions}, 03-DB §5.2/§5.5).
 * <p>
 * Roles are data, not code (04-API §4 "không hard-code"): {@code ADMIN}, {@code COORDINATOR} and
 * {@code ENGINEER} are seeded rows and an administrator can create more. The only business
 * operation is {@link #replacePermissions(Collection)}, which swaps the whole set at once so the
 * audit trail can record a clean before/after picture (TC-RBAC-004).
 */
@Entity
@Table(name = "roles")
public class Role extends AuditableEntity {

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description")
    private String description;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    @BatchSize(size = 50)
    private Set<Permission> permissions = new HashSet<>();

    protected Role() {
    }

    private Role(UUID id, String code, String name, String description) {
        super(id);
        this.code = code;
        this.name = name;
        this.description = description;
    }

    /** Creates a role with no permissions; they are assigned in a second step (or by seed). */
    public static Role create(String code, String name, String description) {
        // Locale.ROOT: "i".toUpperCase() is "İ" in a Turkish default locale (same rule as User.normalize).
        return new Role(UUID.randomUUID(), Objects.requireNonNull(code, "code").trim().toUpperCase(Locale.ROOT),
                Objects.requireNonNull(name, "name").trim(), description);
    }

    /** Replaces the permission set atomically; unknown codes must be rejected by the caller first. */
    public void replacePermissions(Collection<Permission> newPermissions) {
        this.permissions.clear();
        this.permissions.addAll(newPermissions);
    }

    /** Sorted permission codes, ready for the JWT claim and for DTOs. */
    public List<String> permissionCodes() {
        return permissions.stream().map(Permission::getCode).sorted().toList();
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Set<Permission> getPermissions() {
        return Set.copyOf(permissions);
    }
}
