package com.opscenter.identity.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.identity.testsupport.IdentityIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * User administration end to end (04-API §4, D-08, D-26): login -> paginated list -> create (with
 * idempotent replay) -> read -> update with optimistic locking (409 on a stale version) ->
 * lock/unlock (sessions revoked, outbox event, audit) -> role assignment -> soft delete.
 * Assertions on the shared seeded database are written so that rows created by other test
 * classes cannot break them.
 */
class UserControllerIT extends IdentityIntegrationTest {

    @Test
    void list_isPaginatedAndFilterable() throws Exception {
        String admin = adminToken();

        MvcResult page = mvc.perform(get("/api/v1/users?page=0&size=2&sort=username,asc").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems", greaterThanOrEqualTo(4)))
                .andExpect(jsonPath("$.totalPages", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist())
                .andReturn();
        List<String> usernames = new java.util.ArrayList<>();
        for (JsonNode item : body(page).get("items")) {
            usernames.add(item.get("username").asString());
        }
        assertThat(usernames).as("sort=username,asc is applied").isSorted();

        mvc.perform(get("/api/v1/users?q=engineer.&status=ACTIVE").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].username", hasItems("engineer.a", "engineer.b")));

        // LIKE wildcards in q are literal characters, not "match everything"
        mvc.perform(get("/api/v1/users?q=%25").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));

        mvc.perform(get("/api/v1/users?size=500").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        mvc.perform(get("/api/v1/users?status=NOPE").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void list_refusesSortingByPropertiesOutsideTheWhitelist() throws Exception {
        String admin = adminToken();

        // ordering by the hash would be an oracle over the password hashes
        mvc.perform(get("/api/v1/users?sort=passwordHash,asc").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/users?sort=noSuchProperty").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/users?sort=createdAt,desc").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void create_isIdempotentWithTheHeader_andRejectsDuplicatesAndUnknownRoles() throws Exception {
        String admin = adminToken();
        String username = unique("idem-");
        Map<String, Object> request = Map.of("username", username, "email", username + "@opscenter.local",
                "displayName", "Idem", "password", NEW_USER_PASSWORD, "roleCodes", List.of("ENGINEER"));
        String key = "user-" + username;

        MvcResult first = mvc.perform(jsonRequest(post("/api/v1/users"), admin, request).header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[0]").value("ENGINEER"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        String id = body(first).get("id").asString();

        mvc.perform(jsonRequest(post("/api/v1/users"), admin, request).header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        assertThat(jdbc.queryForObject("select count(*) from users where username = ?", Integer.class, username)).isEqualTo(1);
        // keys of internal calls are namespaced by the caller (actorId:key)
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where idempotency_key = ?",
                Integer.class, ADMIN_ID + ":" + key)).isEqualTo(1);

        Map<String, Object> otherBody = Map.of("username", username + "x", "email", username + "x@opscenter.local",
                "displayName", "Idem", "password", NEW_USER_PASSWORD, "roleCodes", List.of("ENGINEER"));
        mvc.perform(jsonRequest(post("/api/v1/users"), admin, otherBody).header("Idempotency-Key", key))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mvc.perform(jsonRequest(post("/api/v1/users"), admin, request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_USERNAME_TAKEN"));
        Map<String, Object> dupEmail = Map.of("username", username + "2", "email", username + "@opscenter.local",
                "displayName", "Idem", "password", NEW_USER_PASSWORD, "roleCodes", List.of());
        mvc.perform(jsonRequest(post("/api/v1/users"), admin, dupEmail))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_EMAIL_TAKEN"));
        Map<String, Object> badRole = Map.of("username", username + "3", "email", username + "3@opscenter.local",
                "displayName", "Idem", "password", NEW_USER_PASSWORD, "roleCodes", List.of("GHOST"));
        mvc.perform(jsonRequest(post("/api/v1/users"), admin, badRole))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("select count(*) from users where username like ?", Integer.class, username + "%"))
                .as("failed creates must not leave rows behind").isEqualTo(1);
        assertThat(jdbc.queryForObject("select password_hash from users where id = ?::uuid", String.class, id))
                .startsWith("{bcrypt}$2a$10$");

        // an oversized key is refused up front instead of overflowing the column
        mvc.perform(jsonRequest(post("/api/v1/users"), admin, request).header("Idempotency-Key", "k".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Integer audited = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'USER_CREATED' and resource_id = ?::uuid and actor_id = ?",
                Integer.class, id, ADMIN_ID);
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void create_withAKeyThatFailedWithABusinessError_recordsTheStatusAndCanBeRetried() throws Exception {
        String admin = adminToken();
        String username = unique("idemf-");
        String key = "user-fail-" + username;
        Map<String, Object> clash = Map.of("username", "admin", "email", username + "@opscenter.local",
                "displayName", "Idem", "password", NEW_USER_PASSWORD, "roleCodes", List.of());

        mvc.perform(jsonRequest(post("/api/v1/users"), admin, clash).header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_USERNAME_TAKEN"));
        Map<String, Object> row = jdbc.queryForMap(
                "select status, response_code from idempotency_keys where idempotency_key = ?", ADMIN_ID + ":" + key);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("response_code")).isEqualTo(409);

        Map<String, Object> fixed = Map.of("username", username, "email", username + "@opscenter.local",
                "displayName", "Idem", "password", NEW_USER_PASSWORD, "roleCodes", List.of());
        mvc.perform(jsonRequest(post("/api/v1/users"), admin, fixed).header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username));
    }

    @Test
    void get_update_andOptimisticLocking() throws Exception {
        String admin = adminToken();
        JsonNode created = createUser(admin, unique("upd-"), List.of("ENGINEER"));
        String id = created.get("id").asString();

        mvc.perform(get("/api/v1/users/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.teams").isArray());

        mvc.perform(jsonRequest(patch("/api/v1/users/" + id), admin, Map.of("displayName", "Renamed", "version", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed"))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(jsonRequest(patch("/api/v1/users/" + id), admin, Map.of("displayName", "Stale", "version", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENCY_VERSION_CONFLICT"));

        mvc.perform(jsonRequest(patch("/api/v1/users/" + id), admin, Map.of("email", "admin@opscenter.local", "version", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_EMAIL_TAKEN"));

        mvc.perform(get("/api/v1/users/" + UUID.randomUUID()).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        assertThat(jdbc.queryForObject("select updated_by from users where id = ?::uuid", UUID.class, id)).isEqualTo(ADMIN_ID);
        assertThat(jdbc.queryForObject("select created_by from users where id = ?::uuid", UUID.class, id)).isEqualTo(ADMIN_ID);
    }

    @Test
    void lock_revokesSessions_emitsOutboxEvent_andUnlockRestoresAccess() throws Exception {
        String admin = adminToken();
        String username = unique("lock-");
        String id = createUser(admin, username, List.of("ENGINEER")).get("id").asString();
        String victimToken = tokenOf(username, NEW_USER_PASSWORD);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(victimToken))).andExpect(status().isOk());

        mvc.perform(jsonRequest(post("/api/v1/users/" + id + "/lock"), admin, Map.of("reason", "suspicious activity")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));

        // D-03: the victim's token dies immediately
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(victimToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_REVOKED"));
        assertThat(jdbc.queryForObject(
                "select count(*) from user_sessions where user_id = ?::uuid and revoked_at is null", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from refresh_tokens where user_id = ?::uuid and revoked_at is null", Integer.class, id)).isZero();

        // D-14: outbox row written in the same transaction, audited with before/after
        Map<String, Object> outbox = jdbc.queryForMap(
                "select event_type, status, payload::text as payload from outbox_events where aggregate_id = ?::uuid", id);
        assertThat(outbox.get("event_type")).isEqualTo("UserLocked");
        assertThat(outbox.get("status")).isEqualTo("PENDING");
        JsonNode payload = json.readTree((String) outbox.get("payload"));
        assertThat(payload.get("username").asString()).isEqualTo(username);
        assertThat(payload.get("reason").asString()).isEqualTo("suspicious activity");
        assertThat(payload.get("lockedBy").asString()).isEqualTo(ADMIN_ID.toString());
        Map<String, Object> audit = jdbc.queryForMap(
                "select before_data::text as b, after_data::text as a from audit_logs where action = 'USER_LOCKED' and resource_id = ?::uuid", id);
        assertThat(json.readTree((String) audit.get("b")).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(json.readTree((String) audit.get("a")).get("status").asString()).isEqualTo("LOCKED");

        mvc.perform(jsonRequest(post("/api/v1/users/" + id + "/lock"), admin, Map.of()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("USER_NOT_ACTIVE"));
        mvc.perform(jsonRequest(post("/api/v1/users/" + ADMIN_ID + "/lock"), admin, Map.of()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("USER_CANNOT_LOCK_SELF"));

        mvc.perform(post("/api/v1/users/" + id + "/unlock").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/v1/users/" + id + "/unlock").header("Authorization", bearer(admin)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("USER_NOT_LOCKED"));
        // the user must log in again (old session stays revoked) and then works normally
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(victimToken))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(tokenOf(username, NEW_USER_PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    void assignRoles_replacesRoles_andIsAudited() throws Exception {
        String admin = adminToken();
        String id = createUser(admin, unique("roles-"), List.of("ENGINEER")).get("id").asString();

        mvc.perform(jsonRequest(put("/api/v1/users/" + id + "/roles"), admin, Map.of("roleCodes", List.of("COORDINATOR", "ENGINEER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("COORDINATOR", "ENGINEER")));
        mvc.perform(jsonRequest(put("/api/v1/users/" + id + "/roles"), admin, Map.of("roleCodes", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isEmpty());
        mvc.perform(jsonRequest(put("/api/v1/users/" + id + "/roles"), admin, Map.of("roleCodes", List.of("GHOST"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));

        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'USER_ROLES_CHANGED' and resource_id = ?::uuid", Integer.class, id))
                .isEqualTo(2);
    }

    /** D-26: soft delete needs {@code user.delete} (ADMIN only), never the caller's own account. */
    @Test
    void D26_delete_isASoftDelete_thatHidesTheUserAndKillsSessions() throws Exception {
        String admin = adminToken();
        String username = unique("del-");
        String id = createUser(admin, username, List.of("ENGINEER")).get("id").asString();
        String victimToken = tokenOf(username, NEW_USER_PASSWORD);

        // COORDINATOR holds user.read but not user.delete
        mvc.perform(delete("/api/v1/users/" + id).header("Authorization", bearer(coordinatorToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));

        mvc.perform(delete("/api/v1/users/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/users/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/users?q=" + username).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(victimToken)))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/users/" + ADMIN_ID).header("Authorization", bearer(admin)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("USER_CANNOT_DELETE_SELF"));

        Map<String, Object> row = jdbc.queryForMap("select status, deleted_at from users where id = ?::uuid", id);
        assertThat(row.get("status")).isEqualTo("DISABLED");
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'USER_DELETED' and resource_id = ?::uuid", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void roleAndPermissionCatalogues_areReadableWithTheRightPermission() throws Exception {
        String admin = adminToken();

        mvc.perform(get("/api/v1/roles").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItems("ADMIN", "COORDINATOR", "ENGINEER")))
                .andExpect(jsonPath("$[?(@.code == 'ADMIN')].permissions[*]", hasSize(16)))
                .andExpect(jsonPath("$[?(@.code == 'ENGINEER')].permissions[*]", hasSize(2)));
        mvc.perform(get("/api/v1/permissions").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(16)))
                .andExpect(jsonPath("$[*].code", hasItems("user.delete", "team.member.manage")))
                .andExpect(jsonPath("$[0].code").value("organization.read"))
                .andExpect(jsonPath("$[0].resource").value("organization"))
                .andExpect(jsonPath("$[0].action").value("read"));
        mvc.perform(get("/api/v1/roles").header("Authorization", bearer(coordinatorToken())))
                .andExpect(status().isOk());
    }
}
