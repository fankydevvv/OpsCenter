package com.opscenter.servicecatalog.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.identity.application.UserLookup;
import com.opscenter.identity.application.UserRef;
import com.opscenter.identity.domain.IdentityErrorCodes;
import com.opscenter.organization.application.OrganizationLookup;
import com.opscenter.organization.application.TeamLookup;
import com.opscenter.organization.application.TeamRef;
import com.opscenter.organization.domain.OrganizationErrorCodes;
import com.opscenter.servicecatalog.domain.CatalogService;
import com.opscenter.servicecatalog.domain.EnvironmentCode;
import com.opscenter.servicecatalog.domain.ServiceCatalogAuditActions;
import com.opscenter.servicecatalog.domain.ServiceCatalogErrorCodes;
import com.opscenter.servicecatalog.domain.ServiceCode;
import com.opscenter.servicecatalog.domain.ServiceEnvironment;
import com.opscenter.servicecatalog.domain.ServiceOwnerSpec;
import com.opscenter.servicecatalog.infrastructure.ServiceEnvironmentRepository;
import com.opscenter.servicecatalog.infrastructure.ServiceRepository;
import com.opscenter.shared.application.CurrentUser;
import com.opscenter.shared.application.IdempotencyService;
import com.opscenter.shared.application.IdempotentOperation;
import com.opscenter.shared.application.IdempotentResult;
import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.InvalidRequestException;
import com.opscenter.shared.domain.NotFoundException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Write use cases of the service catalog (04-API §5, blueprint §7.1, D-32..D-36, D-52).
 * <p>
 * Every command follows the base recipe (03-DB §27): load, check the client's {@code version}
 * (409 {@code CONCURRENCY_VERSION_CONFLICT}), apply the change through the aggregate so its rules
 * run, {@code saveAndFlush} so constraint violations surface inside this method, write the audit
 * line with before/after snapshots in the <em>same</em> transaction, and - only after the commit -
 * delete the Redis resolution cache of the service ({@link #evictAfterCommit}; evicting before the
 * commit could let a concurrent alert re-cache the old state).
 * <p>
 * Owners are validated against other modules through their lookup ports (D-37): unknown team/user
 * = 404, inactive/locked = 422 {@code SERVICE_OWNER_INACTIVE}, a team of another organization =
 * 400 {@code SERVICE_OWNER_TARGET_INVALID}.
 */
@Service
public class ServiceCatalogService {

    static final String RESOURCE_TYPE = "Service";
    static final String ENVIRONMENT_RESOURCE_TYPE = "ServiceEnvironment";

    private final ServiceRepository services;
    private final ServiceEnvironmentRepository environments;
    private final OrganizationLookup organizations;
    private final TeamLookup teams;
    private final UserLookup users;
    private final ServiceViews views;
    private final MetadataJson metadata;
    private final ServiceResolutionCache cache;
    private final IdempotencyService idempotency;
    private final AuditRecorder audit;
    private final CurrentUser currentUser;
    private final EntityManager entityManager;
    private final Clock clock;

    public ServiceCatalogService(ServiceRepository services, ServiceEnvironmentRepository environments,
                                 OrganizationLookup organizations, TeamLookup teams, UserLookup users,
                                 ServiceViews views, MetadataJson metadata, ServiceResolutionCache cache,
                                 IdempotencyService idempotency, AuditRecorder audit, CurrentUser currentUser,
                                 EntityManager entityManager, Clock clock) {
        this.services = services;
        this.environments = environments;
        this.organizations = organizations;
        this.teams = teams;
        this.users = users;
        this.views = views;
        this.metadata = metadata;
        this.cache = cache;
        this.idempotency = idempotency;
        this.audit = audit;
        this.currentUser = currentUser;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // --- create (idempotent) ----------------------------------------------------------------

    /**
     * {@code POST /api/v1/services}. Not {@code @Transactional}: {@link IdempotencyService} claims
     * the {@code Idempotency-Key} in its own short transaction first and then opens the business
     * transaction itself (same pattern as {@code TeamService.create}).
     */
    public IdempotentResult<ServiceDetail> create(CreateServiceCommand command, String idempotencyKey) {
        return idempotency.execute(idempotencyKey, idempotency.hashOf(command), RESOURCE_TYPE,
                new IdempotentOperation<>() {
                    @Override
                    public ServiceDetail create() {
                        return doCreate(command);
                    }

                    @Override
                    public UUID resourceId(ServiceDetail created) {
                        return created.id();
                    }

                    @Override
                    public ServiceDetail reload(UUID resourceId) {
                        return views.detail(load(resourceId));
                    }
                });
    }

    private ServiceDetail doCreate(CreateServiceCommand command) {
        UUID organizationId = organizations.defaultOrganizationId();
        String code = ServiceCode.require(command.code());
        if (services.existsByOrganizationIdAndCode(organizationId, code)) {
            throw codeTaken(code);
        }
        CatalogService.assertOwnersDiffer(command.primaryOwnerId(), command.backupOwnerId());
        validateOwners(organizationId, setOf(command.owningTeamId()),
                setOf(command.primaryOwnerId(), command.backupOwnerId()));
        List<ServiceEnvironment> newEnvironments = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();

        CatalogService service = CatalogService.create(organizationId, code, command.name(), command.description(),
                command.status(), metadata.toJson("metadata", command.metadata()));
        service.assignMainOwners(command.owningTeamId(), command.primaryOwnerId(), command.backupOwnerId());
        for (int i = 0; i < command.environments().size(); i++) {
            EnvironmentSpec spec = command.environments().get(i);
            String environmentCode = EnvironmentCode.require(spec.environmentCode());
            if (!seenCodes.add(environmentCode)) {
                // "prod" and "PRODUCTION" in the same body are the same environment (D-33)
                throw environmentExists(code, environmentCode);
            }
            newEnvironments.add(ServiceEnvironment.create(service.getId(), environmentCode, spec.status(),
                    spec.healthEndpoint(), spec.metricEndpoint(), spec.dashboardUrl(),
                    metadata.toJson("environments[" + i + "].metadata", spec.metadata())));
        }
        try {
            services.saveAndFlush(service);
        }
        catch (DataIntegrityViolationException raceLost) {
            // A concurrent request created the same code between the check and the insert.
            throw codeTaken(code);
        }
        environments.saveAllAndFlush(newEnvironments);

        ServiceDetail created = views.detail(service);
        record(ServiceCatalogAuditActions.SERVICE_CREATED, RESOURCE_TYPE, service.getId(), null, created, organizationId);
        // A cached "NONE" for this code (alerts that arrived before the service was registered) must go.
        evictAfterCommit(organizationId, code);
        return created;
    }

    // --- update / soft delete ---------------------------------------------------------------

    /** {@code PATCH /api/v1/services/{id}} - descriptive fields and {@code active} (D-35, D-36). */
    @Transactional
    public ServiceDetail update(UUID id, UpdateServiceCommand command) {
        CatalogService service = load(id);
        service.assertVersion(command.version());
        ServiceDetail before = views.detail(service);
        if (command.name() != null) {
            service.rename(command.name());
        }
        if (command.description() != null) {
            service.describe(command.description());
        }
        if (command.status() != null) {
            service.changeStatus(command.status());
        }
        if (command.metadata() != null) {
            service.replaceMetadata(metadata.toJson("metadata", command.metadata()));
        }
        boolean deactivated = false;
        if (command.active() != null) {
            if (!command.active() && service.isActive()) {
                service.deactivate(clock.instant());
                deactivated = true;
            }
            else if (command.active() && !service.isActive()) {
                service.activate();
            }
        }
        services.saveAndFlush(service);
        ServiceDetail after = views.detail(service);
        record(deactivated ? ServiceCatalogAuditActions.SERVICE_DEACTIVATED : ServiceCatalogAuditActions.SERVICE_UPDATED,
                RESOURCE_TYPE, service.getId(), before, after, service.getOrganizationId());
        evictAfterCommit(service.getOrganizationId(), service.getCode());
        return after;
    }

    // --- ownership (PUT = full replacement, D-35) --------------------------------------------

    @Transactional
    public ServiceDetail replaceOwnership(UUID id, ReplaceOwnershipCommand command) {
        CatalogService service = load(id);
        service.assertVersion(command.version());
        List<ServiceOwnerSpec> specs = command.additionalOwners().stream()
                .map(o -> new ServiceOwnerSpec(o.teamId(), o.userId(), o.ownershipType(),
                        o.priority() == null ? 1 : o.priority()))
                .toList();
        CatalogService.assertOwnersDiffer(command.primaryOwnerId(), command.backupOwnerId());
        CatalogService.assertNoDuplicates(specs);

        Set<UUID> teamIds = setOf(command.owningTeamId());
        Set<UUID> userIds = setOf(command.primaryOwnerId(), command.backupOwnerId());
        for (ServiceOwnerSpec spec : specs) {
            addIfPresent(teamIds, spec.teamId());
            addIfPresent(userIds, spec.userId());
        }
        validateOwners(service.getOrganizationId(), teamIds, userIds);

        ServiceDetail before = views.detail(service);
        String signatureBefore = service.ownershipSignature();
        long versionBefore = service.getVersion();
        service.assignMainOwners(command.owningTeamId(), command.primaryOwnerId(), command.backupOwnerId());
        service.replaceAdditionalOwners(specs);
        services.saveAndFlush(service);
        if (!signatureBefore.equals(service.ownershipSignature()) && service.getVersion() == versionBefore) {
            // Hibernate bumps the version when a column of services or the owner set changes; a
            // priority-only change updates just a service_owners row. The version must still move,
            // otherwise a client holding the old version could silently overwrite it (03-DB §28).
            entityManager.lock(service, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        }
        ServiceDetail after = views.detail(service);
        record(ServiceCatalogAuditActions.SERVICE_OWNERS_CHANGED, RESOURCE_TYPE, service.getId(), before, after,
                service.getOrganizationId());
        // the cached resolution carries owning_team_id (routing input)
        evictAfterCommit(service.getOrganizationId(), service.getCode());
        return after;
    }

    // --- environments -----------------------------------------------------------------------

    /** {@code POST /api/v1/services/{id}/environments} (TC-SVC-002, TC-SVC-003). */
    @Transactional
    public ServiceEnvironmentView addEnvironment(UUID serviceId, EnvironmentSpec spec) {
        CatalogService service = load(serviceId);
        String environmentCode = EnvironmentCode.require(spec.environmentCode());
        if (environments.existsByServiceIdAndEnvironmentCode(service.getId(), environmentCode)) {
            throw environmentExists(service.getCode(), environmentCode);
        }
        ServiceEnvironment environment = ServiceEnvironment.create(service.getId(), environmentCode, spec.status(),
                spec.healthEndpoint(), spec.metricEndpoint(), spec.dashboardUrl(),
                metadata.toJson("metadata", spec.metadata()));
        try {
            environments.saveAndFlush(environment);
        }
        catch (DataIntegrityViolationException raceLost) {
            throw environmentExists(service.getCode(), environmentCode);
        }
        ServiceEnvironmentView created = views.environment(environment);
        record(ServiceCatalogAuditActions.SERVICE_ENVIRONMENT_CREATED, ENVIRONMENT_RESOURCE_TYPE, environment.getId(),
                null, created, service.getOrganizationId());
        evictAfterCommit(service.getOrganizationId(), service.getCode());
        return created;
    }

    /** {@code PATCH /api/v1/service-environments/{id}}: endpoints, status, metadata, {@code active}. */
    @Transactional
    public ServiceEnvironmentView updateEnvironment(UUID environmentId, UpdateEnvironmentCommand command) {
        ServiceEnvironment environment = environments.findById(environmentId)
                .orElseThrow(() -> new NotFoundException(ServiceCatalogErrorCodes.SERVICE_ENVIRONMENT_NOT_FOUND,
                        "Service environment " + environmentId + " not found"));
        environment.assertVersion(command.version());
        CatalogService service = load(environment.getServiceId());
        ServiceEnvironmentView before = views.environment(environment);
        if (command.status() != null) {
            environment.changeStatus(command.status());
        }
        if (command.healthEndpoint() != null) {
            environment.changeHealthEndpoint(command.healthEndpoint());
        }
        if (command.metricEndpoint() != null) {
            environment.changeMetricEndpoint(command.metricEndpoint());
        }
        if (command.dashboardUrl() != null) {
            environment.changeDashboardUrl(command.dashboardUrl());
        }
        if (command.metadata() != null) {
            environment.replaceMetadata(metadata.toJson("metadata", command.metadata()));
        }
        if (command.active() != null) {
            if (command.active()) {
                environment.activate();
            }
            else {
                environment.deactivate(clock.instant());
            }
        }
        environments.saveAndFlush(environment);
        ServiceEnvironmentView after = views.environment(environment);
        record(ServiceCatalogAuditActions.SERVICE_ENVIRONMENT_UPDATED, ENVIRONMENT_RESOURCE_TYPE, environment.getId(),
                before, after, service.getOrganizationId());
        evictAfterCommit(service.getOrganizationId(), service.getCode());
        return after;
    }

    // --- helpers ----------------------------------------------------------------------------

    private CatalogService load(UUID id) {
        return services.findById(id)
                .orElseThrow(() -> new NotFoundException(ServiceCatalogErrorCodes.SERVICE_NOT_FOUND,
                        "Service " + id + " not found"));
    }

    /**
     * Owners must exist (404), belong to the service's organization (400) and be able to act (422).
     * Batch lookups: one query for all teams, one for all users.
     */
    private void validateOwners(UUID organizationId, Set<UUID> teamIds, Set<UUID> userIds) {
        Map<UUID, TeamRef> foundTeams = teams.findTeams(teamIds);
        for (UUID teamId : teamIds) {
            TeamRef team = foundTeams.get(teamId);
            if (team == null) {
                throw new NotFoundException(OrganizationErrorCodes.TEAM_NOT_FOUND, "Team " + teamId + " not found");
            }
            if (!organizationId.equals(team.organizationId())) {
                throw new InvalidRequestException(ServiceCatalogErrorCodes.SERVICE_OWNER_TARGET_INVALID,
                        "Team " + team.code() + " belongs to another organization");
            }
            if (!team.active()) {
                throw new BusinessRuleException(ServiceCatalogErrorCodes.SERVICE_OWNER_INACTIVE,
                        "Team " + team.code() + " is inactive and cannot own a service");
            }
        }
        Map<UUID, UserRef> foundUsers = users.findUsers(userIds);
        for (UUID userId : userIds) {
            UserRef user = foundUsers.get(userId);
            if (user == null || user.deleted()) {
                throw new NotFoundException(IdentityErrorCodes.USER_NOT_FOUND, "User " + userId + " not found");
            }
            if (!user.active()) {
                throw new BusinessRuleException(ServiceCatalogErrorCodes.SERVICE_OWNER_INACTIVE,
                        "User " + user.username() + " is not active and cannot own a service");
            }
        }
    }

    private void record(String action, String resourceType, UUID resourceId, Object before, Object after,
                        UUID organizationId) {
        audit.record(action, resourceType, resourceId, before, after, currentUser.actorId().orElse(null),
                organizationId);
    }

    /**
     * Deletes the Redis resolution cache of (organization, code) once the transaction has committed
     * (D-52). Outside a transaction (unit tests) it evicts immediately.
     */
    private void evictAfterCommit(UUID organizationId, String code) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.evict(organizationId, code);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.evict(organizationId, code);
            }
        });
    }

    private static ConflictException codeTaken(String code) {
        return new ConflictException(ServiceCatalogErrorCodes.SERVICE_CODE_TAKEN,
                "Service code '" + code + "' already exists");
    }

    private static ConflictException environmentExists(String serviceCode, String environmentCode) {
        return new ConflictException(ServiceCatalogErrorCodes.SERVICE_ENVIRONMENT_EXISTS,
                "Service '" + serviceCode + "' already has environment " + environmentCode
                        + " (a deactivated one is reactivated with PATCH active=true)");
    }

    private static Set<UUID> setOf(UUID... ids) {
        Set<UUID> result = new LinkedHashSet<>();
        for (UUID id : ids) {
            addIfPresent(result, id);
        }
        return result;
    }

    private static void addIfPresent(Set<UUID> target, UUID id) {
        if (id != null) {
            target.add(id);
        }
    }
}
