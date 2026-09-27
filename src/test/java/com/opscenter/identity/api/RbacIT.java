package com.opscenter.identity.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.identity.testsupport.IdentityIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 07-TC §7 RBAC cases end to end with the seeded roles: permissions come from the JWT, are
 * enforced by {@code @PreAuthorize} on the controllers (FR-IAM-04), and role/permission changes
 * are audited with before/after snapshots (TC-AUD-002) and become effective on refresh (D-07).
 */
class RbacIT extends IdentityIntegrationTest {

    @Test
    void TC_RBAC_001_engineerCallingUserAdministration_gets403() throws Exception {
        String engineer = engineerToken();

        mvc.perform(get("/api/v1/users").header("Authorization", bearer(engineer)).header("X-Request-Id", "tc-rbac-001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"))
                .andExpect(jsonPath("$.requestId").value("tc-rbac-001"))
                .andExpect(jsonPath("$.status").value(403));
        // valid body on purpose: @PreAuthorize runs after body validation (an invalid body would be 400)
        mvc.perform(jsonRequest(post("/api/v1/users"), engineer, Map.of("username", "rbac1.user",
                        "email", "rbac1.user@opscenter.local", "displayName", "R", "password", NEW_USER_PASSWORD,
                        "roleCodes", List.of())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));
        mvc.perform(get("/api/v1/roles").header("Authorization", bearer(engineer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/permissions").header("Authorization", bearer(engineer)))
                .andExpect(status().isForbidden());
        // what the engineer IS allowed to do still works
        mvc.perform(get("/api/v1/teams").header("Authorization", bearer(engineer)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/organizations").header("Authorization", bearer(engineer)))
                .andExpect(status().isOk());
    }

    @Test
    void TC_RBAC_002_coordinatorWithPermission_managesTeamMembers() throws Exception {
        // Adapted: the base has no incident assignment yet; the equivalent "succeeds if the role
        // has the permission" case is team.member.manage held by COORDINATOR.
        String coordinator = coordinatorToken();
        JsonNode team = createTeam(coordinator, unique("RBAC2"));

        mvc.perform(jsonRequest(post("/api/v1/teams/" + team.get("id").asString() + "/members"), coordinator,
                        Map.of("userId", ENGINEER_B_ID.toString(), "memberType", "SECONDARY")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.members[0].username").value("engineer.b"))
                .andExpect(jsonPath("$.members[0].memberType").value("SECONDARY"));

        // ...but the coordinator has no user.create, no user.lock and no user.delete (D-26)
        mvc.perform(jsonRequest(post("/api/v1/users/" + ENGINEER_B_ID + "/lock"), coordinator, Map.of()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/users/" + ENGINEER_B_ID).header("Authorization", bearer(coordinator)))
                .andExpect(status().isForbidden());
    }

    @Test
    void TC_RBAC_003_hiddenUiButtonDoesNotMatter_backendEnforcesOnDirectCalls() throws Exception {
        String engineer = engineerToken();

        // an engineer trying to edit their own record, lock a colleague or grant themselves a role
        mvc.perform(jsonRequest(patch("/api/v1/users/" + ENGINEER_A_ID), engineer,
                        Map.of("displayName", "Hacker", "version", 0)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));
        mvc.perform(jsonRequest(post("/api/v1/users/" + ENGINEER_B_ID + "/lock"), engineer, Map.of()))
                .andExpect(status().isForbidden());
        mvc.perform(jsonRequest(put("/api/v1/users/" + ENGINEER_A_ID + "/roles"), engineer,
                        Map.of("roleCodes", List.of("ADMIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/users/" + ENGINEER_B_ID).header("Authorization", bearer(engineer)))
                .andExpect(status().isForbidden());
        mvc.perform(jsonRequest(put("/api/v1/roles/" + ROLE_ENGINEER_ID + "/permissions"), engineer,
                        Map.of("permissionCodes", List.of("user.read"))))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("select display_name from users where id = ?", String.class, ENGINEER_A_ID))
                .isEqualTo("Engineer A");
    }

    @Test
    void TC_RBAC_004_adminChangesRolePermissions_auditHasBeforeAndAfter() throws Exception {
        String admin = adminToken();
        String code = unique("AUD").toUpperCase().replace('-', '_');
        MvcResult created = mvc.perform(jsonRequest(post("/api/v1/roles"), admin,
                        Map.of("code", code, "name", "Audit role", "description", "TC-RBAC-004")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permissions").isEmpty())
                .andReturn();
        UUID roleId = UUID.fromString(body(created).get("id").asString());

        mvc.perform(jsonRequest(put("/api/v1/roles/" + roleId + "/permissions"), admin,
                        Map.of("permissionCodes", List.of("team.read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("team.read")));
        mvc.perform(jsonRequest(put("/api/v1/roles/" + roleId + "/permissions"), admin,
                        Map.of("permissionCodes", List.of("team.read", "user.read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("team.read", "user.read")));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select before_data::text as before_data, after_data::text as after_data, actor_id "
                        + "from audit_logs where action = 'ROLE_PERMISSIONS_CHANGED' and resource_id = ? "
                        + "order by created_at asc", roleId);
        assertThat(rows).hasSize(2);
        JsonNode firstBefore = json.readTree((String) rows.get(0).get("before_data"));
        JsonNode firstAfter = json.readTree((String) rows.get(0).get("after_data"));
        JsonNode secondBefore = json.readTree((String) rows.get(1).get("before_data"));
        JsonNode secondAfter = json.readTree((String) rows.get(1).get("after_data"));
        assertThat(firstBefore.get("permissions")).isEmpty();
        assertThat(firstAfter.get("permissions")).extracting(JsonNode::asString).containsExactly("team.read");
        assertThat(secondBefore.get("permissions")).extracting(JsonNode::asString).containsExactly("team.read");
        assertThat(secondAfter.get("permissions")).extracting(JsonNode::asString)
                .containsExactly("team.read", "user.read");
        assertThat(rows.get(0).get("actor_id")).isEqualTo(ADMIN_ID);
        // TC-AUD-004 by construction: a role snapshot has no secret-looking properties at all
        assertThat(firstAfter.propertyNames()).containsExactlyInAnyOrder("id", "code", "name", "description",
                "permissions", "version");

        mvc.perform(jsonRequest(put("/api/v1/roles/" + roleId + "/permissions"), admin,
                        Map.of("permissionCodes", List.of("no.such.permission"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERMISSION_NOT_FOUND"));
    }

    @Test
    void D07_permissionChangeBecomesEffectiveOnNextRefresh() throws Exception {
        String admin = adminToken();
        String code = unique("D07").toUpperCase().replace('-', '_');
        MvcResult created = mvc.perform(jsonRequest(post("/api/v1/roles"), admin,
                        Map.of("code", code, "name", "D-07 role")))
                .andExpect(status().isCreated()).andReturn();
        UUID roleId = UUID.fromString(body(created).get("id").asString());
        String username = unique("d07-");
        createUser(admin, username, List.of(code));

        JsonNode session = login(username, NEW_USER_PASSWORD);
        String access = session.get("accessToken").asString();
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(access)))
                .andExpect(status().isForbidden());

        mvc.perform(jsonRequest(put("/api/v1/roles/" + roleId + "/permissions"), admin,
                        Map.of("permissionCodes", List.of("user.read"))))
                .andExpect(status().isOk());

        // the old token still carries the old claims...
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(access)))
                .andExpect(status().isForbidden());
        // ...a refresh re-reads roles/permissions from the database
        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", session.get("refreshToken").asString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.permissions", hasItem("user.read")))
                .andReturn();
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(body(refreshed).get("accessToken").asString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(body(refreshed).get("accessToken").asString())))
                .andExpect(jsonPath("$.permissions", not(hasItem("user.lock"))));
    }
}
