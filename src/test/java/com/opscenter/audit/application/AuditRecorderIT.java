package com.opscenter.audit.application;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.audit.domain.AuditAction;
import com.opscenter.audit.domain.AuditLog;
import com.opscenter.audit.infrastructure.AuditLogRepository;
import com.opscenter.shared.infrastructure.web.RequestIdFilter;
import com.opscenter.support.AbstractIntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 03-DB §21 / 07-TC §20: audit rows are written inside the business transaction with actor,
 * request id and JSONB before/after snapshots; TC-AUD-004 secrets never reach the table;
 * TC-AUD-005 neither the entity nor the repository offers a way to change or delete a row.
 * Rows created here are removed with SQL - on purpose, there is no repository method for it.
 */
class AuditRecorderIT extends AbstractIntegrationTest {

    @Autowired
    AuditRecorder recorder;

    @Autowired
    AuditLogRepository repository;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper jsonMapper;

    private final List<UUID> written = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : written) {
            jdbc.update("delete from audit_logs where id = ?", id);
        }
        written.clear();
        jdbc.update("delete from users where username like 'audit-probe-%'");
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    record UserSnapshot(String username, String displayName, String status, List<String> roles) {
    }

    /** {@code audit_logs.actor_id} references {@code users}, so a real (probe) user row is needed. */
    private UUID insertProbeUser() {
        UUID id = UUID.randomUUID();
        String username = "audit-probe-" + id.toString().substring(0, 8);
        jdbc.update("insert into users (id, username, email, password_hash, display_name, status) values (?, ?, ?, ?, ?, ?)",
                id, username, username + "@opscenter.local", "{noop}not-a-real-hash", "Audit Probe", "ACTIVE");
        return id;
    }

    @Test
    void recordsBeforeAfterSnapshotsWithRequestIdAndExplicitActor() {
        UUID actor = insertProbeUser();
        UUID resource = UUID.randomUUID();
        MDC.put(RequestIdFilter.MDC_KEY, "audit-req-1");
        UserSnapshot before = new UserSnapshot("engineer.a", "Engineer A", "ACTIVE", List.of("ENGINEER"));
        UserSnapshot after = new UserSnapshot("engineer.a", "Engineer A", "LOCKED", List.of("ENGINEER"));

        AuditLog saved = tx.execute(s -> recorder.record(AuditAction.USER_LOCKED, "User", resource, before, after,
                actor, null));
        written.add(saved.getId());

        AuditLog row = repository.findById(saved.getId()).orElseThrow();
        assertThat(row.getAction()).isEqualTo("USER_LOCKED");
        assertThat(row.getResourceType()).isEqualTo("User");
        assertThat(row.getResourceId()).isEqualTo(resource);
        assertThat(row.getActorId()).isEqualTo(actor);
        assertThat(row.getRequestId()).isEqualTo("audit-req-1");
        // jsonb stores a canonical form (key order/spacing may differ), so compare parsed trees
        JsonNode beforeJson = jsonMapper.readTree(row.getBeforeData());
        JsonNode afterJson = jsonMapper.readTree(row.getAfterData());
        assertThat(beforeJson.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(afterJson.get("status").asString()).isEqualTo("LOCKED");
        assertThat(afterJson.get("roles").get(0).asString()).isEqualTo("ENGINEER");
        assertThat(jsonMapper.treeToValue(afterJson, UserSnapshot.class)).isEqualTo(after);
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(repository.findByResourceTypeAndResourceIdOrderByCreatedAtDesc("User", resource)).hasSize(1);
    }

    @Test
    void actorIsNullForSystemActionsWithoutAuthentication() {
        AuditLog saved = tx.execute(s -> recorder.record(AuditAction.AUTH_LOGIN_FAILED, "User", null,
                null, Map.of("login", "ghost", "reason", "USER_NOT_FOUND")));
        written.add(saved.getId());

        AuditLog row = repository.findById(saved.getId()).orElseThrow();
        assertThat(row.getActorId()).isNull();
        assertThat(row.getBeforeData()).isNull();
        assertThat(jsonMapper.readTree(row.getAfterData()).get("login").asString()).isEqualTo("ghost");
    }

    @Test
    void TC_AUD_004_snapshotContainingSecretOrToken_isRejectedAndNothingIsWritten() {
        long before = repository.count();
        Map<String, Object> leaking = Map.of("username", "admin", "passwordHash", "{bcrypt}$2a$10$abc");
        Map<String, Object> nested = Map.of("session", Map.of("refreshToken", "opaque-value"));

        assertThatThrownBy(() -> tx.execute(s -> recorder.record(AuditAction.USER_CREATED, "User", UUID.randomUUID(), null, leaking)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passwordHash");
        assertThatThrownBy(() -> tx.execute(s -> recorder.record(AuditAction.AUTH_LOGIN_SUCCESS, "User", UUID.randomUUID(), nested, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session.refreshToken");
        assertThat(repository.count()).isEqualTo(before);
    }

    @Test
    void recordOutsideTransaction_isRejected() {
        assertThatThrownBy(() -> recorder.record(AuditAction.USER_UPDATED, "User", UUID.randomUUID(), null, Map.of("a", 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * TC-AUD-005 "the business API cannot delete audit records": the entity has no setter and the
     * persistence API exposes no {@code delete*} at all - a module that wanted to delete would
     * have to write SQL, which no business code does.
     */
    @Test
    void TC_AUD_005_auditLogEntityAndRepositoryAreAppendOnly() {
        List<String> setters = Arrays.stream(AuditLog.class.getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("set"))
                .toList();
        assertThat(setters).isEmpty();

        List<String> repositoryMethods = Arrays.stream(AuditLogRepository.class.getMethods())
                .map(Method::getName)
                .toList();
        assertThat(repositoryMethods).noneMatch(name -> name.startsWith("delete"));
        assertThat(repositoryMethods).contains("save", "findById");
    }
}
