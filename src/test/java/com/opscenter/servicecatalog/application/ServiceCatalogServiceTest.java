package com.opscenter.servicecatalog.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.opscenter.servicecatalog.domain.OwnershipType;
import com.opscenter.servicecatalog.domain.ServiceCatalogAuditActions;
import com.opscenter.servicecatalog.domain.ServiceCatalogErrorCodes;
import com.opscenter.servicecatalog.domain.ServiceEnvironment;
import com.opscenter.servicecatalog.domain.ServiceStatus;
import com.opscenter.servicecatalog.infrastructure.ServiceEnvironmentRepository;
import com.opscenter.servicecatalog.infrastructure.ServiceRepository;
import com.opscenter.shared.application.CurrentUser;
import com.opscenter.shared.application.IdempotencyService;
import com.opscenter.shared.application.IdempotentOperation;
import com.opscenter.shared.application.IdempotentResult;
import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.DomainException;
import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.domain.InvalidRequestException;
import com.opscenter.shared.domain.NotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Business rules of {@link ServiceCatalogService} with mocked repositories and lookup ports
 * (blueprint D-33..D-37, D-52): owner validation, code/environment conflicts, metadata guard,
 * optimistic locking, audit actions and cache eviction.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServiceCatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @Mock ServiceRepository services;
    @Mock ServiceEnvironmentRepository environments;
    @Mock OrganizationLookup organizations;
    @Mock TeamLookup teams;
    @Mock UserLookup users;
    @Mock ServiceResolutionCache cache;
    @Mock IdempotencyService idempotency;
    @Mock AuditRecorder audit;
    @Mock CurrentUser currentUser;
    @Mock EntityManager entityManager;

    private final Map<UUID, TeamRef> knownTeams = new HashMap<>();
    private final Map<UUID, UserRef> knownUsers = new HashMap<>();
    private final List<ServiceEnvironment> savedEnvironments = new ArrayList<>();
    private ServiceCatalogService catalog;
    private TeamRef platform;
    private UserRef alice;
    private UserRef bob;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        platform = new TeamRef(UUID.randomUUID(), ORG, "PLATFORM", "Team Platform", true);
        alice = new UserRef(UUID.randomUUID(), "alice", "Alice", true, false);
        bob = new UserRef(UUID.randomUUID(), "bob", "Bob", true, false);
        knownTeams.put(platform.id(), platform);
        knownUsers.put(alice.id(), alice);
        knownUsers.put(bob.id(), bob);

        when(organizations.defaultOrganizationId()).thenReturn(ORG);
        when(teams.findTeams(anyCollection())).thenAnswer(inv -> filter(knownTeams, inv.getArgument(0)));
        when(users.findUsers(anyCollection())).thenAnswer(inv -> filter(knownUsers, inv.getArgument(0)));
        when(services.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(environments.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(environments.saveAllAndFlush(anyList())).thenAnswer(inv -> {
            savedEnvironments.addAll(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(environments.findByServiceIdOrderByEnvironmentCodeAsc(any())).thenAnswer(inv -> savedEnvironments);
        when(currentUser.actorId()).thenReturn(Optional.of(ACTOR));
        when(idempotency.hashOf(any())).thenReturn("hash");
        when(idempotency.execute(any(), any(), any(), any(IdempotentOperation.class)))
                .thenAnswer(inv -> IdempotentResult.created(((IdempotentOperation<ServiceDetail>) inv.getArgument(3)).create()));

        MetadataJson metadata = new MetadataJson(JsonMapper.builder().build());
        ServiceViews views = new ServiceViews(environments, teams, users, metadata);
        catalog = new ServiceCatalogService(services, environments, organizations, teams, users, views, metadata, cache,
                idempotency, audit, currentUser, entityManager, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static <T> Map<UUID, T> filter(Map<UUID, T> known, java.util.Collection<UUID> ids) {
        Map<UUID, T> result = new HashMap<>();
        ids.forEach(id -> {
            if (known.containsKey(id)) {
                result.put(id, known.get(id));
            }
        });
        return result;
    }

    private CreateServiceCommand command(String code, UUID team, UUID primary, UUID backup, Map<String, Object> metadata,
                                         List<EnvironmentSpec> envs) {
        return new CreateServiceCommand(code, "Payment API", "Handles payments", null, team, primary, backup, metadata, envs);
    }

    private static EnvironmentSpec env(String code) {
        return new EnvironmentSpec(code, null, null, null, null, null);
    }

    private static String codeOf(Throwable ex) {
        return ((DomainException) ex).code();
    }

    // --- create -------------------------------------------------------------------------------

    @Test
    void TC_SVC_001_create_normalisesCodes_setsOwners_auditsAndEvictsTheCache() {
        ServiceDetail created = catalog.create(command(" Payment-API ", platform.id(), alice.id(), bob.id(),
                Map.of("tier", "gold"), List.of(env("prod"), env("dev"))), "key-1").value();

        assertThat(created.code()).isEqualTo("payment-api");
        assertThat(created.organizationId()).isEqualTo(ORG);
        assertThat(created.status()).isEqualTo(ServiceStatus.ACTIVE);
        assertThat(created.owningTeam()).isEqualTo(new TeamBrief(platform.id(), "PLATFORM", "Team Platform"));
        assertThat(created.primaryOwner().username()).isEqualTo("alice");
        assertThat(created.backupOwner().username()).isEqualTo("bob");
        assertThat(created.metadata().get("tier").asString()).isEqualTo("gold");
        assertThat(savedEnvironments).extracting(ServiceEnvironment::getEnvironmentCode)
                .containsExactly("PRODUCTION", "DEV");
        verify(audit).record(eq(ServiceCatalogAuditActions.SERVICE_CREATED), eq("Service"), eq(created.id()), isNull(),
                eq(created), eq(ACTOR), eq(ORG));
        verify(cache).evict(ORG, "payment-api");
    }

    @Test
    void create_withTakenCode_is409_andNothingIsSaved() {
        when(services.existsByOrganizationIdAndCode(ORG, "payment-api")).thenReturn(true);

        assertThatThrownBy(() -> catalog.create(command("payment-api", null, null, null, null, List.of()), null))
                .isInstanceOf(ConflictException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(ServiceCatalogErrorCodes.SERVICE_CODE_TAKEN);
        verify(services, never()).saveAndFlush(any());
    }

    @Test
    void TC_SVC_003_aliasAndCanonicalCodeInOneBody_areTheSameEnvironment() {
        assertThatThrownBy(() -> catalog.create(command("payment-api", null, null, null, null,
                List.of(env("prod"), env("PRODUCTION"))), null))
                .isInstanceOf(ConflictException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(ServiceCatalogErrorCodes.SERVICE_ENVIRONMENT_EXISTS);
        verify(services, never()).saveAndFlush(any());
    }

    @Test
    void create_rejectsSamePrimaryAndBackupOwner() {
        assertThatThrownBy(() -> catalog.create(command("payment-api", null, alice.id(), alice.id(), null, List.of()), null))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(ServiceCatalogErrorCodes.SERVICE_OWNERS_MUST_DIFFER);
    }

    @Test
    void create_validatesTheOwningTeamThroughTheLookupPort() {
        UUID unknownTeam = UUID.randomUUID();
        assertThatThrownBy(() -> catalog.create(command("a1", unknownTeam, null, null, null, List.of()), null))
                .isInstanceOf(NotFoundException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(OrganizationErrorCodes.TEAM_NOT_FOUND);

        TeamRef inactive = new TeamRef(UUID.randomUUID(), ORG, "OLD", "Old team", false);
        knownTeams.put(inactive.id(), inactive);
        assertThatThrownBy(() -> catalog.create(command("a2", inactive.id(), null, null, null, List.of()), null))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(ServiceCatalogErrorCodes.SERVICE_OWNER_INACTIVE);

        TeamRef foreign = new TeamRef(UUID.randomUUID(), UUID.randomUUID(), "OTHER", "Other org team", true);
        knownTeams.put(foreign.id(), foreign);
        assertThatThrownBy(() -> catalog.create(command("a3", foreign.id(), null, null, null, List.of()), null))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(ServiceCatalogErrorCodes.SERVICE_OWNER_TARGET_INVALID);
    }

    @Test
    void create_rejectsLockedOrDeletedOwners() {
        UserRef locked = new UserRef(UUID.randomUUID(), "locked", "Locked", false, false);
        UserRef deleted = new UserRef(UUID.randomUUID(), "gone", "Gone", false, true);
        knownUsers.put(locked.id(), locked);
        knownUsers.put(deleted.id(), deleted);

        assertThatThrownBy(() -> catalog.create(command("b1", null, locked.id(), null, null, List.of()), null))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(ServiceCatalogErrorCodes.SERVICE_OWNER_INACTIVE);
        assertThatThrownBy(() -> catalog.create(command("b2", null, null, deleted.id(), null, List.of()), null))
                .isInstanceOf(NotFoundException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(IdentityErrorCodes.USER_NOT_FOUND);
    }

    @Test
    void create_rejectsCredentialLikeOrOversizedMetadata() {
        assertThatThrownBy(() -> catalog.create(command("c1", null, null, null, Map.of("apiKey", "x"), List.of()), null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("credential")
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(ErrorCodes.VALIDATION_FAILED);
        assertThatThrownBy(() -> catalog.create(command("c2", null, null, null,
                Map.of("nested", Map.of("password", "x")), List.of()), null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> catalog.create(command("c3", null, null, null,
                Map.of("blob", "x".repeat(MetadataJson.MAX_BYTES)), List.of()), null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("8192");
    }

    // --- update / ownership -------------------------------------------------------------------

    private CatalogService existing() {
        CatalogService service = CatalogService.create(ORG, "payment-api", "Payment API", null, null, null);
        when(services.findById(service.getId())).thenReturn(Optional.of(service));
        return service;
    }

    @Test
    void update_withStaleVersion_is409() {
        CatalogService service = existing();

        assertThatThrownBy(() -> catalog.update(service.getId(), new UpdateServiceCommand("X", null, null, null, null, 7)))
                .isInstanceOf(ConflictException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(ErrorCodes.CONCURRENCY_VERSION_CONFLICT);
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void update_activeFalse_isTheSoftDelete_auditedAsDeactivated() {
        CatalogService service = existing();

        ServiceDetail after = catalog.update(service.getId(),
                new UpdateServiceCommand(null, "", ServiceStatus.MAINTENANCE, null, false, 0));

        assertThat(after.active()).isFalse();
        assertThat(after.deletedAt()).isEqualTo(NOW);
        assertThat(after.status()).isEqualTo(ServiceStatus.MAINTENANCE);
        verify(audit).record(eq(ServiceCatalogAuditActions.SERVICE_DEACTIVATED), eq("Service"), eq(service.getId()),
                any(ServiceDetail.class), eq(after), eq(ACTOR), eq(ORG));
        verify(cache).evict(ORG, "payment-api");
    }

    @Test
    void TC_SVC_005_replaceOwnership_replacesEverything_andAuditsBeforeAfter() {
        CatalogService service = existing();

        ServiceDetail after = catalog.replaceOwnership(service.getId(), new ReplaceOwnershipCommand(platform.id(),
                alice.id(), null, List.of(new AdditionalOwnerSpec(null, bob.id(), OwnershipType.ON_CALL_CONTACT, null),
                new AdditionalOwnerSpec(platform.id(), null, OwnershipType.SUPPORTING_TEAM, 3)), 0));

        assertThat(after.owningTeam().code()).isEqualTo("PLATFORM");
        assertThat(after.primaryOwner().username()).isEqualTo("alice");
        assertThat(after.backupOwner()).isNull();
        assertThat(after.owners()).extracting(ServiceOwnerView::ownershipType)
                .containsExactly(OwnershipType.SUPPORTING_TEAM, OwnershipType.ON_CALL_CONTACT);
        assertThat(after.owners().get(0).team().code()).isEqualTo("PLATFORM");
        assertThat(after.owners().get(0).priority()).isEqualTo(3);
        assertThat(after.owners().get(1).user().displayName()).isEqualTo("Bob");
        assertThat(after.owners().get(1).priority()).as("null priority defaults to 1").isEqualTo(1);
        ArgumentCaptor<Object> before = ArgumentCaptor.forClass(Object.class);
        verify(audit).record(eq(ServiceCatalogAuditActions.SERVICE_OWNERS_CHANGED), eq("Service"), eq(service.getId()),
                before.capture(), eq(after), eq(ACTOR), eq(ORG));
        assertThat(((ServiceDetail) before.getValue()).owningTeam()).isNull();
        verify(cache).evict(ORG, "payment-api");
        // no Hibernate here, so the version did not move by itself: the service forces the increment
        verify(entityManager).lock(service, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

    @Test
    void replaceOwnership_rejectsDuplicatesAndInvalidTargets_beforeTouchingTheAggregate() {
        CatalogService service = existing();

        assertThatThrownBy(() -> catalog.replaceOwnership(service.getId(), new ReplaceOwnershipCommand(null, null, null,
                List.of(new AdditionalOwnerSpec(null, bob.id(), OwnershipType.TECHNICAL_OWNER, 1),
                        new AdditionalOwnerSpec(null, bob.id(), OwnershipType.TECHNICAL_OWNER, 2)), 0)))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(ServiceCatalogErrorCodes.SERVICE_OWNER_DUPLICATE);
        assertThatThrownBy(() -> catalog.replaceOwnership(service.getId(), new ReplaceOwnershipCommand(null, null, null,
                List.of(new AdditionalOwnerSpec(platform.id(), bob.id(), OwnershipType.TECHNICAL_OWNER, 1)), 0)))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(ServiceCatalogErrorCodes.SERVICE_OWNER_TARGET_INVALID);
        assertThat(service.getOwners()).isEmpty();
        verify(services, never()).saveAndFlush(any());
    }

    // --- environments -------------------------------------------------------------------------

    @Test
    void TC_SVC_002_addEnvironment_normalisesTheAlias_andTC_SVC_003_rejectsADuplicate() {
        CatalogService service = existing();
        when(environments.existsByServiceIdAndEnvironmentCode(service.getId(), "PRODUCTION")).thenReturn(false, true);

        ServiceEnvironmentView created = catalog.addEnvironment(service.getId(),
                new EnvironmentSpec("prod", null, "https://pay.example.com/health", null, null, null));

        assertThat(created.environmentCode()).isEqualTo("PRODUCTION");
        assertThat(created.serviceId()).isEqualTo(service.getId());
        verify(audit).record(eq(ServiceCatalogAuditActions.SERVICE_ENVIRONMENT_CREATED), eq("ServiceEnvironment"),
                eq(created.id()), isNull(), eq(created), eq(ACTOR), eq(ORG));
        assertThatThrownBy(() -> catalog.addEnvironment(service.getId(), env("PRD")))
                .isInstanceOf(ConflictException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(ServiceCatalogErrorCodes.SERVICE_ENVIRONMENT_EXISTS);
    }

    @Test
    void unknownServiceOrEnvironment_is404() {
        UUID unknown = UUID.randomUUID();
        when(services.findById(unknown)).thenReturn(Optional.empty());
        when(environments.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalog.addEnvironment(unknown, env("DEV")))
                .isInstanceOf(NotFoundException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(ServiceCatalogErrorCodes.SERVICE_NOT_FOUND);
        assertThatThrownBy(() -> catalog.updateEnvironment(unknown,
                new UpdateEnvironmentCommand(null, null, null, null, null, false, 0)))
                .isInstanceOf(NotFoundException.class)
                .extracting(ServiceCatalogServiceTest::codeOf).isEqualTo(ServiceCatalogErrorCodes.SERVICE_ENVIRONMENT_NOT_FOUND);
    }
}
