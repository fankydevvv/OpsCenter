package com.opscenter.servicecatalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.opscenter.shared.domain.AuditableEntity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One environment of a service ({@code service_environments}, 03-DB §38.2 canonical, blueprint
 * D-32): {@code odoo-erp} in {@code DEV} has other health/metric/dashboard URLs than in
 * {@code PRODUCTION}, so those endpoints live here and not on the service.
 * <p>
 * Its own aggregate (own version, own {@code PATCH}); it refers to its service by id. The
 * environment code is canonical upper case ({@link EnvironmentCode}) and immutable - it is the
 * second half of the alert-to-catalog join ({@code service=odoo-erp, environment=DEV}). URLs are
 * checked with {@link EndpointUrl} (http/https only).
 */
@Entity
@Table(name = "service_environments")
public class ServiceEnvironment extends AuditableEntity {

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "environment_code", nullable = false, updatable = false, length = 50)
    private String environmentCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ServiceStatus status;

    @Column(name = "health_endpoint", length = 500)
    private String healthEndpoint;

    @Column(name = "metric_endpoint", length = 500)
    private String metricEndpoint;

    @Column(name = "dashboard_url", length = 500)
    private String dashboardUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private String metadata;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ServiceEnvironment() {
    }

    private ServiceEnvironment(UUID id, UUID serviceId, String environmentCode) {
        super(id);
        this.serviceId = serviceId;
        this.environmentCode = environmentCode;
        this.status = ServiceStatus.ACTIVE;
        this.active = true;
    }

    /**
     * @param environmentCode already canonical ({@link EnvironmentCode#require(String)})
     */
    public static ServiceEnvironment create(UUID serviceId, String environmentCode, ServiceStatus status,
                                            String healthEndpoint, String metricEndpoint, String dashboardUrl,
                                            String metadataJson) {
        if (!EnvironmentCode.isValid(environmentCode)) {
            throw new IllegalArgumentException("Environment code must be canonical and valid: " + environmentCode);
        }
        ServiceEnvironment environment = new ServiceEnvironment(UUID.randomUUID(),
                Objects.requireNonNull(serviceId, "serviceId"), environmentCode);
        environment.status = status == null ? ServiceStatus.ACTIVE : status;
        environment.healthEndpoint = EndpointUrl.check("healthEndpoint", healthEndpoint);
        environment.metricEndpoint = EndpointUrl.check("metricEndpoint", metricEndpoint);
        environment.dashboardUrl = EndpointUrl.check("dashboardUrl", dashboardUrl);
        environment.metadata = metadataJson;
        return environment;
    }

    public void changeStatus(ServiceStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "status");
    }

    /** Blank clears the URL. */
    public void changeHealthEndpoint(String url) {
        this.healthEndpoint = EndpointUrl.check("healthEndpoint", url);
    }

    public void changeMetricEndpoint(String url) {
        this.metricEndpoint = EndpointUrl.check("metricEndpoint", url);
    }

    public void changeDashboardUrl(String url) {
        this.dashboardUrl = EndpointUrl.check("dashboardUrl", url);
    }

    public void replaceMetadata(String metadataJson) {
        this.metadata = metadataJson;
    }

    /** Soft delete (03-DB §29): alerts of this environment still resolve to the service but "not registered". */
    public void deactivate(Instant now) {
        if (!active) {
            return;
        }
        this.active = false;
        this.deletedAt = Objects.requireNonNull(now, "now");
    }

    public void activate() {
        this.active = true;
        this.deletedAt = null;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public String getEnvironmentCode() {
        return environmentCode;
    }

    public ServiceStatus getStatus() {
        return status;
    }

    public String getHealthEndpoint() {
        return healthEndpoint;
    }

    public String getMetricEndpoint() {
        return metricEndpoint;
    }

    public String getDashboardUrl() {
        return dashboardUrl;
    }

    public String getMetadata() {
        return metadata;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
