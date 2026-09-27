package com.opscenter.identity.domain;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One permission code such as {@code user.read} ({@code permissions}, 03-DB §5.3, 04-API §4).
 * <p>
 * Permissions are <em>reference data</em>: they describe what the code base can enforce (every
 * {@code @PreAuthorize("hasAuthority('...')")} names one), so they are created by seed migrations
 * together with the feature, never through the API. That is why this entity has no audit columns,
 * no version and no mutators (D-10). The code is copied verbatim into the JWT {@code permissions}
 * claim (D-06).
 */
@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "resource", nullable = false, length = 100)
    private String resource;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    protected Permission() {
    }

    public Permission(UUID id, String code, String resource, String action) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = Objects.requireNonNull(code, "code");
        this.resource = Objects.requireNonNull(resource, "resource");
        this.action = Objects.requireNonNull(action, "action");
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getResource() {
        return resource;
    }

    public String getAction() {
        return action;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof Permission that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return Permission.class.hashCode();
    }
}
