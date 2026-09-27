package com.opscenter.identity;

import java.util.List;
import java.util.Map;

import com.opscenter.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V005 + V006 seed (blueprint §6, D-26, 07-TC §5): fixed ids, 16 permissions, three roles with
 * the documented mappings, and - only because the test profile enables {@code db/seed-dev}
 * (D-27) - four DEV accounts whose hashes verify against the documented passwords plus the
 * PAYMENT/PLATFORM memberships. Also proves Flyway V001-V006 apply in order on a clean database.
 */
class SeedIdentityIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void flywayAppliedSeedAfterSchema_andDevAccountsFromTheirOwnLocation() {
        List<String> scripts = jdbc.queryForList(
                "select script from flyway_schema_history where success order by installed_rank", String.class);
        assertThat(scripts).containsSubsequence("V004__create_idempotency_outbox.sql", "V005__seed_identity.sql",
                "V005_1__seed_dev_accounts.sql", "V006__review_fixes.sql");
    }

    @Test
    void permissionsRolesAndMappingsMatchTheBlueprint() {
        assertThat(jdbc.queryForObject("select count(*) from permissions", Integer.class)).isEqualTo(16);
        Map<String, Integer> byRole = Map.of("ADMIN", 16, "COORDINATOR", 8, "ENGINEER", 2);
        byRole.forEach((role, expected) -> assertThat(jdbc.queryForObject(
                "select count(*) from role_permissions rp join roles r on r.id = rp.role_id where r.code = ?",
                Integer.class, role)).as(role).isEqualTo(expected));
        assertThat(jdbc.queryForList(
                "select p.code from role_permissions rp join roles r on r.id = rp.role_id join permissions p on p.id = rp.permission_id "
                        + "where r.code = 'ENGINEER' order by p.code", String.class))
                .containsExactly("organization.read", "team.read");
        // D-26: user.delete exists and belongs to ADMIN only
        assertThat(jdbc.queryForList(
                "select r.code from role_permissions rp join roles r on r.id = rp.role_id join permissions p on p.id = rp.permission_id "
                        + "where p.code = 'user.delete'", String.class))
                .containsExactly("ADMIN");
        assertThat(jdbc.queryForObject("select name from roles where code = 'ADMIN'", String.class)).isEqualTo("Administrator");
    }

    @Test
    void devAccountsExist_withVerifiableBcryptHashes_andSystemAsCreator() {
        List<Map<String, Object>> users = jdbc.queryForList(
                "select username, password_hash, status, created_by from users where username in "
                        + "('admin','coordinator','engineer.a','engineer.b') order by username");
        assertThat(users).hasSize(4);
        Map<String, String> passwords = Map.of("admin", "Admin@123", "coordinator", "Coordinator@123",
                "engineer.a", "Engineer@123", "engineer.b", "Engineer@123");
        for (Map<String, Object> user : users) {
            String username = (String) user.get("username");
            assertThat((String) user.get("password_hash")).startsWith("{bcrypt}$2a$10$");
            assertThat(passwordEncoder.matches(passwords.get(username), (String) user.get("password_hash")))
                    .as("hash of %s", username).isTrue();
            assertThat(user.get("status")).isEqualTo("ACTIVE");
            assertThat(user.get("created_by")).as("seed rows are created by the system").isNull();
        }
        assertThat(jdbc.queryForList(
                "select r.code from user_roles ur join roles r on r.id = ur.role_id join users u on u.id = ur.user_id "
                        + "where u.username = 'coordinator'", String.class)).containsExactly("COORDINATOR");
    }

    @Test
    void organizationAndTeamMembershipsAreSeeded() {
        assertThat(jdbc.queryForObject("select code from organizations where id = '00000000-0000-4000-8000-000000000001'",
                String.class)).isEqualTo("DEFAULT");
        assertThat(jdbc.queryForList("select code from teams order by code", String.class))
                .contains("PAYMENT", "PLATFORM");
        // only the seeded teams: other integration tests may add these users to teams of their own
        List<Map<String, Object>> members = jdbc.queryForList(
                "select t.code as team, u.username, m.member_type, m.is_primary from team_members m "
                        + "join teams t on t.id = m.team_id join users u on u.id = m.user_id "
                        + "where u.username in ('engineer.a','engineer.b','coordinator') "
                        + "and t.code in ('PAYMENT','PLATFORM') order by u.username");
        assertThat(members).extracting(m -> m.get("username") + ":" + m.get("team") + ":" + m.get("member_type") + ":" + m.get("is_primary"))
                .containsExactly("coordinator:PAYMENT:SECONDARY:false", "engineer.a:PAYMENT:PRIMARY:true",
                        "engineer.b:PLATFORM:PRIMARY:true");
    }
}
