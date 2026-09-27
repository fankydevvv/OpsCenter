package com.opscenter.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.opscenter.shared.domain.BusinessRuleException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Domain rules of {@link User} (03-DB §5.1 status machine, D-09/D-10) without Spring or a database. */
class UserTest {

    private static User activeUser() {
        return User.register("Engineer.A", "Engineer.A@OpsCenter.local", "{bcrypt}hash", " Engineer A ");
    }

    @Test
    void register_normalisesIdentifiersAndStartsActive() {
        User user = activeUser();

        assertThat(user.getUsername()).isEqualTo("engineer.a");
        assertThat(user.getEmail()).isEqualTo("engineer.a@opscenter.local");
        assertThat(user.getDisplayName()).isEqualTo("Engineer A");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getId()).isNotNull();
        assertThat(user.isDeleted()).isFalse();
    }

    @Test
    void lock_onlyFromActive_andUnlock_onlyFromLocked() {
        User user = activeUser();

        user.lock();
        assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
        assertThatThrownBy(user::lock)
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_NOT_ACTIVE);

        user.unlock();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThatThrownBy(user::unlock)
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_NOT_LOCKED);
    }

    @Test
    void disabledUser_cannotBeLocked_andIsMarkedDeleted() {
        User user = activeUser();
        Instant now = Instant.parse("2026-09-27T00:00:00Z");

        user.disable(now);

        assertThat(user.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(user.getDeletedAt()).isEqualTo(now);
        assertThat(user.isDeleted()).isTrue();
        assertThatThrownBy(user::lock).hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_NOT_ACTIVE);
    }

    @Test
    void assertCanAuthenticate_revealsLockedOrDisabledOnly() {
        User user = activeUser();
        user.assertCanAuthenticate();

        user.lock();
        assertThatThrownBy(user::assertCanAuthenticate)
                .isInstanceOf(AuthenticationFailedException.class)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_ACCOUNT_LOCKED);

        user.unlock();
        user.disable(Instant.now());
        assertThatThrownBy(user::assertCanAuthenticate)
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.AUTH_ACCOUNT_DISABLED);
    }

    @Test
    void permissionCodes_areTheSortedUnionOfAllRoles() {
        Role admin = Role.create("ADMIN", "Administrator", null);
        admin.replacePermissions(Set.of(permission("user.read"), permission("user.lock")));
        Role coordinator = Role.create("COORDINATOR", "Coordinator", null);
        coordinator.replacePermissions(Set.of(permission("user.read"), permission("team.read")));
        User user = activeUser();

        user.replaceRoles(List.of(admin, coordinator));

        assertThat(user.roleCodes()).containsExactly("ADMIN", "COORDINATOR");
        assertThat(user.permissionCodes()).containsExactly("team.read", "user.lock", "user.read");
    }

    private static Permission permission(String code) {
        int dot = code.indexOf('.');
        return new Permission(UUID.randomUUID(), code, code.substring(0, dot), code.substring(dot + 1));
    }
}
