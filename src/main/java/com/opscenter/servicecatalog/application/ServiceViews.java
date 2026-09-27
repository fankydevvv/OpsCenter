package com.opscenter.servicecatalog.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.opscenter.identity.application.UserLookup;
import com.opscenter.identity.application.UserRef;
import com.opscenter.organization.application.TeamLookup;
import com.opscenter.organization.application.TeamRef;
import com.opscenter.servicecatalog.domain.CatalogService;
import com.opscenter.servicecatalog.domain.ServiceEnvironment;
import com.opscenter.servicecatalog.domain.ServiceOwner;
import com.opscenter.servicecatalog.infrastructure.ServiceEnvironmentRepository;

import org.springframework.stereotype.Component;

/**
 * Assembles service DTOs from entities plus the names of teams and users owned by other modules.
 * <p>
 * The point of this class is the <b>batch</b>: for a page of 20 services it asks
 * {@link TeamLookup} and {@link UserLookup} once each with all ids of the page, and loads all
 * environments of the page with one query - three extra queries per page instead of three per
 * row (the N+1 problem, R-20). Called inside the caller's transaction.
 */
@Component
public class ServiceViews {

    private final ServiceEnvironmentRepository environments;
    private final TeamLookup teams;
    private final UserLookup users;
    private final MetadataJson metadata;

    public ServiceViews(ServiceEnvironmentRepository environments, TeamLookup teams, UserLookup users,
                        MetadataJson metadata) {
        this.environments = environments;
        this.teams = teams;
        this.users = users;
        this.metadata = metadata;
    }

    public List<ServiceSummary> summaries(List<CatalogService> services) {
        if (services.isEmpty()) {
            return List.of();
        }
        List<UUID> serviceIds = services.stream().map(CatalogService::getId).toList();
        Map<UUID, List<ServiceEnvironment>> environmentsByService = environments
                .findByServiceIdInOrderByEnvironmentCodeAsc(serviceIds).stream()
                .collect(Collectors.groupingBy(ServiceEnvironment::getServiceId));
        Map<UUID, TeamRef> teamsById = teams.findTeams(ids(services.stream().map(CatalogService::getOwningTeamId)));
        Map<UUID, UserRef> usersById = users.findUsers(ids(services.stream()
                .flatMap(s -> Stream.of(s.getPrimaryOwnerId(), s.getBackupOwnerId()))));
        List<ServiceSummary> result = new ArrayList<>(services.size());
        for (CatalogService service : services) {
            List<ServiceEnvironmentBrief> envs = environmentsByService.getOrDefault(service.getId(), List.of()).stream()
                    .filter(ServiceEnvironment::isActive)
                    .map(ServiceEnvironmentBrief::from)
                    .toList();
            result.add(new ServiceSummary(service.getId(), service.getCode(), service.getName(), service.getStatus(),
                    service.isActive(), TeamBrief.from(get(teamsById, service.getOwningTeamId())),
                    UserBrief.from(get(usersById, service.getPrimaryOwnerId())),
                    UserBrief.from(get(usersById, service.getBackupOwnerId())), envs, service.getUpdatedAt(),
                    service.getVersion()));
        }
        return result;
    }

    public ServiceDetail detail(CatalogService service) {
        List<ServiceOwner> owners = service.getOwners();
        Set<UUID> teamIds = new HashSet<>();
        Set<UUID> userIds = new HashSet<>();
        addIfPresent(teamIds, service.getOwningTeamId());
        addIfPresent(userIds, service.getPrimaryOwnerId());
        addIfPresent(userIds, service.getBackupOwnerId());
        for (ServiceOwner owner : owners) {
            addIfPresent(teamIds, owner.getTeamId());
            addIfPresent(userIds, owner.getUserId());
        }
        Map<UUID, TeamRef> teamsById = teams.findTeams(teamIds);
        Map<UUID, UserRef> usersById = users.findUsers(userIds);

        List<ServiceOwnerView> ownerViews = owners.stream()
                .map(o -> new ServiceOwnerView(o.getId(), o.getOwnershipType(), o.getPriority(),
                        o.getTeamId() == null ? null : briefOrPlaceholder(teamsById, o.getTeamId()),
                        o.getUserId() == null ? null : userOrPlaceholder(usersById, o.getUserId())))
                .toList();
        List<ServiceEnvironmentView> envViews = environments.findByServiceIdOrderByEnvironmentCodeAsc(service.getId())
                .stream().map(this::environment).toList();

        return new ServiceDetail(service.getId(), service.getOrganizationId(), service.getCode(), service.getName(),
                service.getDescription(), service.getStatus(), service.isActive(),
                TeamBrief.from(get(teamsById, service.getOwningTeamId())),
                UserBrief.from(get(usersById, service.getPrimaryOwnerId())),
                UserBrief.from(get(usersById, service.getBackupOwnerId())),
                metadata.toNode(service.getMetadata()), envViews, ownerViews, service.getCreatedAt(),
                service.getUpdatedAt(), service.getDeletedAt(), service.getVersion());
    }

    public ServiceEnvironmentView environment(ServiceEnvironment environment) {
        return new ServiceEnvironmentView(environment.getId(), environment.getServiceId(),
                environment.getEnvironmentCode(), environment.getStatus(), environment.getHealthEndpoint(),
                environment.getMetricEndpoint(), environment.getDashboardUrl(),
                metadata.toNode(environment.getMetadata()), environment.isActive(), environment.getCreatedAt(),
                environment.getUpdatedAt(), environment.getVersion());
    }

    /** A referenced team that no longer exists still shows its id rather than disappearing. */
    private static TeamBrief briefOrPlaceholder(Map<UUID, TeamRef> teamsById, UUID teamId) {
        TeamRef team = teamsById.get(teamId);
        return team == null ? new TeamBrief(teamId, null, null) : TeamBrief.from(team);
    }

    private static UserBrief userOrPlaceholder(Map<UUID, UserRef> usersById, UUID userId) {
        UserRef user = usersById.get(userId);
        return user == null ? new UserBrief(userId, null, null) : UserBrief.from(user);
    }

    /** {@code Map.of().get(null)} throws, and owner ids are optional - hence this null-safe get. */
    private static <T> T get(Map<UUID, T> byId, UUID id) {
        return id == null ? null : byId.get(id);
    }

    private static Collection<UUID> ids(Stream<UUID> ids) {
        return ids.filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static void addIfPresent(Set<UUID> target, UUID id) {
        if (id != null) {
            target.add(id);
        }
    }
}
