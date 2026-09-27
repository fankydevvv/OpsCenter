package com.opscenter.integration.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.opscenter.shared.domain.AuditableEntity;

/**
 * A registered inbound source ({@code integration_sources}, 03-DB §40.1, blueprint D-38).
 * <p>
 * The row answers three questions for the webhook: which organization do the alerts belong to,
 * is the source enabled, and <em>where</em> is its shared secret ({@code secret_ref}, e.g.
 * {@code env:OPSCENTER_ALERTMANAGER_TOKEN}). The secret itself is never stored in the database
 * (03-DB §3.6): a database dump or a SQL injection must not reveal it. Sprint 2 seeds the
 * {@code alertmanager} row in V008; CRUD through the API comes with the Integration Hub.
 * {@code last_event_at} is updated with a throttled bulk statement, not through this entity.
 */
@Entity
@Table(name = "integration_sources")
public class IntegrationSource extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "code", nullable = false, updatable = false, length = 80)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private IntegrationSourceType sourceType;

    @Column(name = "base_url", length = 1000)
    private String baseUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 20)
    private IntegrationAuthType authType;

    @Column(name = "secret_ref", length = 255)
    private String secretRef;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "health_status", nullable = false, length = 20)
    private IntegrationHealthStatus healthStatus;

    @Column(name = "last_health_check_at")
    private Instant lastHealthCheckAt;

    @Column(name = "last_event_at", insertable = false, updatable = false)
    private Instant lastEventAt;

    protected IntegrationSource() {
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public IntegrationSourceType getSourceType() {
        return sourceType;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public IntegrationAuthType getAuthType() {
        return authType;
    }

    public String getSecretRef() {
        return secretRef;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public IntegrationHealthStatus getHealthStatus() {
        return healthStatus;
    }

    public Instant getLastHealthCheckAt() {
        return lastHealthCheckAt;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }
}
