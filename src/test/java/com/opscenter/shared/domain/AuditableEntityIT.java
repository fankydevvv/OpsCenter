package com.opscenter.shared.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.opscenter.shared.infrastructure.security.JwtClaims;
import com.opscenter.support.AbstractIntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-02 / 03-DB §3.4 / §28 / D-11: the auditable base entity validates against the migrated schema,
 * {@code created_*}/{@code updated_*} are filled by JPA auditing from the JWT subject (or left
 * {@code null} for the system), and {@code version} drives optimistic locking.
 */
class AuditableEntityIT extends AbstractIntegrationTest {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbc.update("delete from roles where code like 'PROBE_%'");
    }

    private static void authenticateAs(UUID userId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256")
                .subject(userId.toString())
                .claim(JwtClaims.SESSION_ID, UUID.randomUUID().toString())
                .claim(JwtClaims.USERNAME, "admin")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("user.read")), "admin"));
    }

    @Test
    void auditColumnsAreFilledFromTheAuthenticatedActor_andVersionIncrementsOnUpdate() {
        UUID actor = UUID.randomUUID();
        authenticateAs(actor);
        RoleProbeEntity created = tx.execute(s -> {
            RoleProbeEntity role = new RoleProbeEntity("PROBE_" + UUID.randomUUID().toString().substring(0, 8), "Probe");
            entityManager.persist(role);
            return role;
        });

        Map<String, Object> row = jdbc.queryForMap("select * from roles where id = ?", created.getId());
        assertThat(row.get("created_by")).isEqualTo(actor);
        assertThat(row.get("updated_by")).isEqualTo(actor);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("version")).isEqualTo(0L);

        UUID otherActor = UUID.randomUUID();
        authenticateAs(otherActor);
        tx.executeWithoutResult(s -> entityManager.find(RoleProbeEntity.class, created.getId()).rename("Renamed"));

        Map<String, Object> updated = jdbc.queryForMap("select * from roles where id = ?", created.getId());
        assertThat(updated.get("name")).isEqualTo("Renamed");
        assertThat(updated.get("created_by")).as("created_by is immutable").isEqualTo(actor);
        assertThat(updated.get("updated_by")).isEqualTo(otherActor);
        assertThat(updated.get("version")).isEqualTo(1L);
    }

    @Test
    void systemActionsLeaveActorColumnsNull() {
        RoleProbeEntity created = tx.execute(s -> {
            RoleProbeEntity role = new RoleProbeEntity("PROBE_SYS_" + UUID.randomUUID().toString().substring(0, 8), "Probe");
            entityManager.persist(role);
            return role;
        });

        Map<String, Object> row = jdbc.queryForMap("select created_by, updated_by from roles where id = ?", created.getId());
        assertThat(row.get("created_by")).isNull();
        assertThat(row.get("updated_by")).isNull();
    }

    @Test
    void staleVersionFromClient_isRejectedWithConflictCode() {
        RoleProbeEntity created = tx.execute(s -> {
            RoleProbeEntity role = new RoleProbeEntity("PROBE_VER_" + UUID.randomUUID().toString().substring(0, 8), "Probe");
            entityManager.persist(role);
            return role;
        });
        tx.executeWithoutResult(s -> entityManager.find(RoleProbeEntity.class, created.getId()).rename("v1"));

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                entityManager.find(RoleProbeEntity.class, created.getId()).assertVersion(0)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCodes.CONCURRENCY_VERSION_CONFLICT);
    }
}
