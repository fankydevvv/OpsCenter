package com.opscenter.servicecatalog.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import com.opscenter.shared.domain.AuditableEntity;
import com.opscenter.shared.domain.InvalidRequestException;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A service of the catalog - the aggregate root of this module ({@code services} +
 * {@code service_owners}; 01-SRS §4, 03-DB §7, blueprint D-32..D-36).
 * <p>
 * Named {@code CatalogService} rather than {@code Service} so it never clashes with Spring's
 * {@code @Service} stereotype in the classes that use both.
 * <p>
 * Rules that live here instead of in a controller or service:
 * <ul>
 *   <li>{@code code} and {@code organization_id} never change ({@code updatable = false}; D-33).</li>
 *   <li>Primary and backup owner must be different people ({@code SERVICE_OWNERS_MUST_DIFFER},
 *       also a CHECK constraint).</li>
 *   <li>Additional owners are replaced as a whole (diffed against the current set) and may not
 *       contain the same (target, ownership type) twice ({@code SERVICE_OWNER_DUPLICATE});
 *       {@code orphanRemoval} deletes the rows that are no longer in the set.</li>
 *   <li>Soft delete = {@link #deactivate(Instant)}: {@code is_active = false} + {@code deleted_at}
 *       (03-DB §29). A deactivated service is never chosen by alert-to-service resolution (D-36).</li>
 * </ul>
 * Environments are a separate aggregate ({@link ServiceEnvironment}) because each one is edited on
 * its own ({@code PATCH /service-environments/{id}}) with its own optimistic-lock version.
 * {@code metadata} is JSONB kept as a JSON string (base convention D-12).
 */
@Entity
@Table(name = "services")
public class CatalogService extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "code", nullable = false, updatable = false, length = 100)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ServiceStatus status;

    @Column(name = "owning_team_id")
    private UUID owningTeamId;

    @Column(name = "primary_owner_id")
    private UUID primaryOwnerId;

    @Column(name = "backup_owner_id")
    private UUID backupOwnerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private String metadata;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "service", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    private Set<ServiceOwner> owners = new HashSet<>();

    protected CatalogService() {
    }

    private CatalogService(UUID id, UUID organizationId, String code, String name) {
        super(id);
        this.organizationId = organizationId;
        this.code = code;
        this.name = name;
        this.status = ServiceStatus.ACTIVE;
        this.active = true;
    }

    /**
     * @param code already normalised and validated with {@link ServiceCode#require(String)}
     */
    public static CatalogService create(UUID organizationId, String code, String name, String description,
                                        ServiceStatus status, String metadataJson) {
        if (!ServiceCode.isValid(code)) {
            throw new IllegalArgumentException("Service code must be normalised and valid: " + code);
        }
        CatalogService service = new CatalogService(UUID.randomUUID(),
                Objects.requireNonNull(organizationId, "organizationId"), code,
                Objects.requireNonNull(name, "name").trim());
        service.description = blankToNull(description);
        service.status = status == null ? ServiceStatus.ACTIVE : status;
        service.metadata = metadataJson;
        return service;
    }

    // --- descriptive fields -----------------------------------------------------------------

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "name").trim();
    }

    /** {@code null} or blank clears the description. */
    public void describe(String newDescription) {
        this.description = blankToNull(newDescription);
    }

    public void changeStatus(ServiceStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "status");
    }

    /** @param metadataJson JSON object text or {@code null} to clear */
    public void replaceMetadata(String metadataJson) {
        this.metadata = metadataJson;
    }

    // --- lifecycle (soft delete, 03-DB §29) -------------------------------------------------

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

    // --- ownership (D-34, D-35) -------------------------------------------------------------

    /**
     * Sets the three "main" owners at once; {@code null} removes one (PUT semantics).
     *
     * @throws InvalidRequestException {@code SERVICE_OWNERS_MUST_DIFFER} when primary equals backup
     */
    public void assignMainOwners(UUID newOwningTeamId, UUID newPrimaryOwnerId, UUID newBackupOwnerId) {
        assertOwnersDiffer(newPrimaryOwnerId, newBackupOwnerId);
        this.owningTeamId = newOwningTeamId;
        this.primaryOwnerId = newPrimaryOwnerId;
        this.backupOwnerId = newBackupOwnerId;
    }

    /**
     * Makes the additional owners equal to {@code specs} (PUT semantics, D-35) by <b>diffing</b>
     * instead of "delete all, insert all":
     * <ol>
     *   <li>owners no longer listed are removed from the set - {@code orphanRemoval} deletes their rows;</li>
     *   <li>owners listed again keep their row (id, {@code created_at}); only the priority may change;</li>
     *   <li>new entries become new rows.</li>
     * </ol>
     * Why not clear-and-re-add: Hibernate flushes inserts <em>before</em> orphan deletes, so re-adding an
     * unchanged owner in the same transaction would violate the partial unique index
     * {@code uk_service_owners_user/team} before the old row is gone.
     *
     * @throws InvalidRequestException {@code SERVICE_OWNER_DUPLICATE} for a repeated (target, type)
     */
    public void replaceAdditionalOwners(List<ServiceOwnerSpec> specs) {
        assertNoDuplicates(specs);
        Map<String, ServiceOwnerSpec> wanted = new LinkedHashMap<>();
        specs.forEach(spec -> wanted.put(spec.targetKey(), spec));

        owners.removeIf(owner -> !wanted.containsKey(owner.targetKey()));
        Set<String> kept = new HashSet<>();
        for (ServiceOwner owner : owners) {
            owner.changePriority(wanted.get(owner.targetKey()).priority());
            kept.add(owner.targetKey());
        }
        for (ServiceOwnerSpec spec : specs) {
            if (!kept.contains(spec.targetKey())) {
                owners.add(new ServiceOwner(this, spec));
            }
        }
    }

    /**
     * Everything that {@code PUT /ownership} can change, as one comparable value. The application
     * service compares it before/after to know whether the request changed anything at all.
     */
    public String ownershipSignature() {
        return owningTeamId + "|" + primaryOwnerId + "|" + backupOwnerId + "|"
                + owners.stream().map(o -> o.targetKey() + ":" + o.getPriority()).sorted().toList();
    }

    public static void assertOwnersDiffer(UUID primaryOwnerId, UUID backupOwnerId) {
        if (primaryOwnerId != null && primaryOwnerId.equals(backupOwnerId)) {
            throw new InvalidRequestException(ServiceCatalogErrorCodes.SERVICE_OWNERS_MUST_DIFFER,
                    "Primary and backup owner must be different users");
        }
    }

    public static void assertNoDuplicates(List<ServiceOwnerSpec> specs) {
        Set<String> seen = new HashSet<>();
        for (ServiceOwnerSpec spec : specs) {
            if (!seen.add(spec.targetKey())) {
                throw new InvalidRequestException(ServiceCatalogErrorCodes.SERVICE_OWNER_DUPLICATE,
                        "Additional owner listed twice: " + spec.targetKey());
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // --- getters ----------------------------------------------------------------------------

    public UUID getOrganizationId() {
        return organizationId;
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

    public ServiceStatus getStatus() {
        return status;
    }

    public UUID getOwningTeamId() {
        return owningTeamId;
    }

    public UUID getPrimaryOwnerId() {
        return primaryOwnerId;
    }

    public UUID getBackupOwnerId() {
        return backupOwnerId;
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

    /** Additional owners ordered by type, then priority - a stable order for DTOs and audit diffs. */
    public List<ServiceOwner> getOwners() {
        return owners.stream()
                .sorted(Comparator.comparing(ServiceOwner::getOwnershipType)
                        .thenComparingInt(ServiceOwner::getPriority)
                        .thenComparing(o -> String.valueOf(o.getTeamId() != null ? o.getTeamId() : o.getUserId())))
                .toList();
    }
}
