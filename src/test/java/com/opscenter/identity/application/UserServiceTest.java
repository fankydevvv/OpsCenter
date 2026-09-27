package com.opscenter.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.audit.domain.AuditAction;
import com.opscenter.identity.domain.IdentityErrorCodes;
import com.opscenter.identity.domain.PasswordHasher;
import com.opscenter.identity.domain.Role;
import com.opscenter.identity.domain.User;
import com.opscenter.identity.domain.UserLockedEvent;
import com.opscenter.identity.domain.UserStatus;
import com.opscenter.identity.infrastructure.persistence.RoleRepository;
import com.opscenter.identity.infrastructure.persistence.UserRepository;
import com.opscenter.shared.application.CurrentUser;
import com.opscenter.shared.application.IdempotencyService;
import com.opscenter.shared.application.IdempotentOperation;
import com.opscenter.shared.application.IdempotentResult;
import com.opscenter.shared.application.OutboxAppender;
import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Business rules of {@link UserService} with mocked persistence: conflict/not-found codes, the
 * self-lock guard, session revocation + outbox + audit on lock, and idempotent create (D-13).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Mock UserRepository users;
    @Mock RoleRepository roles;
    @Mock PasswordHasher passwordHasher;
    @Mock SessionRevocationService revocation;
    @Mock IdempotencyService idempotency;
    @Mock OutboxAppender outbox;
    @Mock AuditRecorder audit;
    @Mock CurrentUser currentUser;
    @Mock TeamMembershipQuery teamMemberships;

    private UserService service;
    private final UUID adminId = UUID.randomUUID();
    private Role engineerRole;
    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        engineerRole = Role.create("ENGINEER", "Engineer", null);
        user = User.register("engineer.a", "engineer.a@opscenter.local", "{bcrypt}x", "Engineer A");
        user.replaceRoles(Set.of(engineerRole));

        when(currentUser.actorId()).thenReturn(Optional.of(adminId));
        when(teamMemberships.membershipsOf(any())).thenReturn(List.of());
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(users.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(roles.findByCodeIn(Set.of("ENGINEER"))).thenReturn(List.of(engineerRole));
        when(passwordHasher.hash(anyString())).thenReturn("{bcrypt}hashed");
        when(idempotency.hashOf(any())).thenReturn("hash");
        // Run the operation directly, as the real service does when no key is given.
        when(idempotency.execute(any(), any(), any(), any(IdempotentOperation.class)))
                .thenAnswer(inv -> IdempotentResult.created(((IdempotentOperation<UserDetail>) inv.getArgument(3)).create()));

        service = new UserService(users, roles, passwordHasher, revocation, idempotency, outbox, audit, currentUser,
                teamMemberships, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void create_hashesPassword_assignsRoles_andAudits() {
        CreateUserCommand command = new CreateUserCommand("New.User", "New.User@OpsCenter.local", "New User",
                "Str0ng-Passw0rd!", List.of("engineer"));

        IdempotentResult<UserDetail> result = service.create(command, null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("new.user");
        assertThat(saved.getValue().getEmail()).isEqualTo("new.user@opscenter.local");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("{bcrypt}hashed");
        assertThat(saved.getValue().roleCodes()).containsExactly("ENGINEER");
        assertThat(result.replayed()).isFalse();
        assertThat(result.value().username()).isEqualTo("new.user");
        verify(idempotency).hashOf(command.fingerprint());
        verify(audit).record(eq(AuditAction.USER_CREATED), eq("User"), eq(saved.getValue().getId()), isNull(), any());
    }

    @Test
    void create_rejectsTakenUsernameAndEmailWithDistinctCodes() {
        when(users.existsByUsername("taken")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new CreateUserCommand("taken", "a@b.c", "A", "Str0ng-Passw0rd!",
                List.of()), null))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_USERNAME_TAKEN);

        when(users.existsByEmail("dup@opscenter.local")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new CreateUserCommand("fresh", "dup@opscenter.local", "A",
                "Str0ng-Passw0rd!", List.of()), null))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_EMAIL_TAKEN);
        verify(users, never()).save(any());
    }

    @Test
    void create_withUnknownRole_isNotFound() {
        when(roles.findByCodeIn(Set.of("ENGINEER", "GHOST"))).thenReturn(List.of(engineerRole));

        assertThatThrownBy(() -> service.create(new CreateUserCommand("x", "x@y.z", "X", "Str0ng-Passw0rd!",
                List.of("ENGINEER", "GHOST")), null))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.ROLE_NOT_FOUND)
                .hasMessageContaining("GHOST");
    }

    @Test
    void lock_ownAccount_isRefused() {
        when(currentUser.actorId()).thenReturn(Optional.of(user.getId()));

        assertThatThrownBy(() -> service.lock(user.getId(), "test"))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_CANNOT_LOCK_SELF);
        verify(revocation, never()).revokeAllForUser(any());
    }

    @Test
    void lock_revokesSessions_appendsOutboxEvent_andAuditsBeforeAfter() {
        UserDetail after = service.lock(user.getId(), "left the company");

        assertThat(after.status()).isEqualTo(UserStatus.LOCKED);
        verify(revocation).revokeAllForUser(user.getId());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(eq("User"), eq(user.getId()), eq("UserLocked"), payload.capture());
        UserLockedEvent event = (UserLockedEvent) payload.getValue();
        assertThat(event.username()).isEqualTo("engineer.a");
        assertThat(event.reason()).isEqualTo("left the company");
        assertThat(event.lockedBy()).isEqualTo(adminId);
        assertThat(event.occurredAt()).isEqualTo(NOW);

        ArgumentCaptor<Object> before = ArgumentCaptor.forClass(Object.class);
        verify(audit).record(eq(AuditAction.USER_LOCKED), eq("User"), eq(user.getId()), before.capture(), any());
        assertThat(((UserDetail) before.getValue()).status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void lock_alreadyLocked_andUnlock_notLocked_areBusinessRuleViolations() {
        user.lock();
        assertThatThrownBy(() -> service.lock(user.getId(), null))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_NOT_ACTIVE);

        user.unlock();
        assertThatThrownBy(() -> service.unlock(user.getId()))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_NOT_LOCKED);
    }

    @Test
    void update_withStaleVersion_isRejectedBeforeAnyChange() {
        assertThatThrownBy(() -> service.update(user.getId(), new UpdateUserCommand("Renamed", null, 7)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCodes.CONCURRENCY_VERSION_CONFLICT);
        assertThat(user.getDisplayName()).isEqualTo("Engineer A");
    }

    @Test
    void update_toAnEmailOfSomeoneElse_conflicts() {
        when(users.existsByEmail("admin@opscenter.local")).thenReturn(true);

        assertThatThrownBy(() -> service.update(user.getId(), new UpdateUserCommand(null, "admin@opscenter.local", 0)))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_EMAIL_TAKEN);
    }

    @Test
    void update_changesProfile_andAuditsBeforeAfter() {
        UserDetail after = service.update(user.getId(), new UpdateUserCommand(" Engineer Alpha ", null, 0));

        assertThat(after.displayName()).isEqualTo("Engineer Alpha");
        verify(audit).record(eq(AuditAction.USER_UPDATED), eq("User"), eq(user.getId()), any(), any());
    }

    @Test
    void assignRoles_replacesTheSet_andAudits() {
        Role admin = Role.create("ADMIN", "Administrator", null);
        when(roles.findByCodeIn(Set.of("ADMIN"))).thenReturn(List.of(admin));

        UserDetail after = service.assignRoles(user.getId(), List.of("ADMIN"));

        assertThat(after.roles()).containsExactly("ADMIN");
        verify(audit).record(eq(AuditAction.USER_ROLES_CHANGED), eq("User"), eq(user.getId()), any(), any());
    }

    @Test
    void list_refusesSortPropertiesOutsideTheWhitelist() {
        assertThatThrownBy(() -> service.list(null, null, PageRequest.of(0, 20, Sort.by("passwordHash"))))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCodes.VALIDATION_FAILED);
        verify(users, never()).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void delete_ownAccount_isRefused() {
        when(currentUser.actorId()).thenReturn(Optional.of(user.getId()));

        assertThatThrownBy(() -> service.delete(user.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_CANNOT_DELETE_SELF);
        verify(revocation, never()).revokeAllForUser(any());
    }

    @Test
    void delete_softDeletes_revokesSessions_andHidesTheUserAfterwards() {
        service.delete(user.getId());

        assertThat(user.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(user.getDeletedAt()).isEqualTo(NOW);
        verify(revocation).revokeAllForUser(user.getId());
        verify(audit).record(eq("USER_DELETED"), eq("User"), eq(user.getId()), any(), any());

        assertThatThrownBy(() -> service.get(user.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_NOT_FOUND);
    }

    @Test
    void get_unknownUser_isNotFound() {
        UUID unknown = UUID.randomUUID();
        when(users.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(unknown))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_NOT_FOUND);
    }
}
