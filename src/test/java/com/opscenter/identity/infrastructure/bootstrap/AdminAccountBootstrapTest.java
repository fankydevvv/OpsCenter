package com.opscenter.identity.infrastructure.bootstrap;

import java.util.Optional;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.audit.domain.AuditAction;
import com.opscenter.identity.domain.PasswordHasher;
import com.opscenter.identity.domain.Role;
import com.opscenter.identity.domain.User;
import com.opscenter.identity.domain.UserStatus;
import com.opscenter.identity.infrastructure.persistence.RoleRepository;
import com.opscenter.identity.infrastructure.persistence.UserRepository;
import com.opscenter.support.TestTransactions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D-27: the first ADMIN of an unseeded environment comes from the operator's own password, once. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAccountBootstrapTest {

    @Mock UserRepository users;
    @Mock RoleRepository roles;
    @Mock PasswordHasher passwordHasher;
    @Mock AuditRecorder audit;

    private final Role adminRole = Role.create("ADMIN", "Administrator", null);

    @BeforeEach
    void setUp() {
        when(roles.findByCode("ADMIN")).thenReturn(Optional.of(adminRole));
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(passwordHasher.hash("Str0ng-Passw0rd!")).thenReturn("{bcrypt}hashed");
    }

    private AdminAccountBootstrap bootstrap(String password) {
        return new AdminAccountBootstrap(new BootstrapAdminProperties("Admin", "admin@opscenter.local", "Administrator",
                password), users, roles, passwordHasher, audit, TestTransactions.passThrough());
    }

    @Test
    void createsTheAdministrator_withHashedPassword_roleAndAuditLine() {
        when(users.countWithRoleAndStatus("ADMIN", UserStatus.ACTIVE)).thenReturn(0L);

        assertThat(bootstrap("Str0ng-Passw0rd!").bootstrap()).isTrue();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("admin");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("{bcrypt}hashed");
        assertThat(saved.getValue().roleCodes()).containsExactly("ADMIN");
        assertThat(saved.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(audit).record(eq(AuditAction.USER_CREATED), eq("User"), eq(saved.getValue().getId()), isNull(), any(),
                isNull(), isNull());
    }

    @Test
    void doesNothingWhenAnActiveAdministratorAlreadyExists() {
        when(users.countWithRoleAndStatus("ADMIN", UserStatus.ACTIVE)).thenReturn(1L);

        assertThat(bootstrap("Str0ng-Passw0rd!").bootstrap()).isFalse();
        verify(users, never()).save(any());
    }

    @Test
    void doesNothingWithoutAPassword_orWithATooShortOne() {
        when(users.countWithRoleAndStatus("ADMIN", UserStatus.ACTIVE)).thenReturn(0L);

        assertThat(bootstrap(null).bootstrap()).isFalse();
        assertThat(bootstrap("  ").bootstrap()).isFalse();
        assertThat(bootstrap("short").bootstrap()).isFalse();
        verify(users, never()).save(any());
    }

    @Test
    void doesNotOverwriteAnExistingAccountWithTheSameUsername() {
        when(users.countWithRoleAndStatus("ADMIN", UserStatus.ACTIVE)).thenReturn(0L);
        when(users.existsByUsername("admin")).thenReturn(true);

        assertThat(bootstrap("Str0ng-Passw0rd!").bootstrap()).isFalse();
        verify(users, never()).save(any());
    }
}
