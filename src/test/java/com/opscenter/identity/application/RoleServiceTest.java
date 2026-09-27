package com.opscenter.identity.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.audit.domain.AuditAction;
import com.opscenter.identity.domain.IdentityErrorCodes;
import com.opscenter.identity.domain.Permission;
import com.opscenter.identity.domain.Role;
import com.opscenter.identity.infrastructure.persistence.PermissionRepository;
import com.opscenter.identity.infrastructure.persistence.RoleRepository;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.NotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link RoleService}: duplicate code, unknown permission, and the before/after audit of TC-RBAC-004. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleServiceTest {

    @Mock RoleRepository roles;
    @Mock PermissionRepository permissions;
    @Mock AuditRecorder audit;

    private RoleService service;
    private Role role;
    private final Permission teamRead = new Permission(UUID.randomUUID(), "team.read", "team", "read");
    private final Permission userRead = new Permission(UUID.randomUUID(), "user.read", "user", "read");

    @BeforeEach
    void setUp() {
        role = Role.create("AUDITOR", "Auditor", "Read-only reviewer");
        role.replacePermissions(Set.of(teamRead));
        when(roles.findById(role.getId())).thenReturn(Optional.of(role));
        when(roles.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(roles.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new RoleService(roles, permissions, audit);
    }

    @Test
    void create_normalisesCode_andRejectsDuplicates() {
        RoleDetail created = service.create(new CreateRoleCommand(" ops_lead ", "Ops Lead", null));
        assertThat(created.code()).isEqualTo("OPS_LEAD");
        verify(audit).record(eq(AuditAction.ROLE_CREATED), eq("Role"), eq(created.id()), any(), any());

        when(roles.existsByCode("AUDITOR")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new CreateRoleCommand("AUDITOR", "Again", null)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.ROLE_CODE_TAKEN);
    }

    @Test
    void TC_RBAC_004_replacePermissions_auditsBeforeAndAfterSnapshots() {
        when(permissions.findByCodeIn(Set.of("user.read"))).thenReturn(List.of(userRead));

        RoleDetail after = service.replacePermissions(role.getId(), List.of("user.read"));

        assertThat(after.permissions()).containsExactly("user.read");
        ArgumentCaptor<Object> before = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> afterSnapshot = ArgumentCaptor.forClass(Object.class);
        verify(audit).record(eq(AuditAction.ROLE_PERMISSIONS_CHANGED), eq("Role"), eq(role.getId()), before.capture(),
                afterSnapshot.capture());
        assertThat(((RoleDetail) before.getValue()).permissions()).containsExactly("team.read");
        assertThat(((RoleDetail) afterSnapshot.getValue()).permissions()).containsExactly("user.read");
    }

    @Test
    void replacePermissions_withUnknownCode_isNotFound_andChangesNothing() {
        when(permissions.findByCodeIn(Set.of("user.read", "nope.x"))).thenReturn(List.of(userRead));

        assertThatThrownBy(() -> service.replacePermissions(role.getId(), List.of("user.read", "nope.x")))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.PERMISSION_NOT_FOUND)
                .hasMessageContaining("nope.x");
        assertThat(role.permissionCodes()).containsExactly("team.read");
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void replacePermissions_onUnknownRole_isNotFound() {
        UUID unknown = UUID.randomUUID();
        when(roles.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replacePermissions(unknown, List.of()))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.ROLE_NOT_FOUND);
    }
}
