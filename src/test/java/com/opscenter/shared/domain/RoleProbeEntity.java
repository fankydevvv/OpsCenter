package com.opscenter.shared.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Test-only entity mapped onto the {@code roles} table to prove that {@link AuditableEntity}
 * matches the 03-DB §3 column conventions under {@code ddl-auto=validate} and that JPA auditing
 * fills the audit columns (R-02). The identity module owns the real {@code Role} entity; two
 * entities on one table are legal for Hibernate and this one lives only in {@code src/test}.
 */
@Entity
@Table(name = "roles")
public class RoleProbeEntity extends AuditableEntity {

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description")
    private String description;

    protected RoleProbeEntity() {
    }

    public RoleProbeEntity(String code, String name) {
        super(UUID.randomUUID());
        this.code = code;
        this.name = name;
    }

    public void rename(String name) {
        this.name = name;
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
}
