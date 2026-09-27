package com.opscenter.organization.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.identity.testsupport.IdentityIntegrationTest;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Team membership end to end (04-API §5/§21, 03-DB §39.2, FR-ORG-02): add/remove members, the
 * duplicate and not-found codes, the audit trail, and the membership showing up in
 * {@code GET /auth/me} through the identity port.
 */
class TeamMemberIT extends IdentityIntegrationTest {

    @Test
    void addAndRemoveMembers_withConflictAndNotFoundCodes() throws Exception {
        String coordinator = coordinatorToken();
        String admin = adminToken();
        JsonNode team = createTeam(coordinator, unique("MEM").replace('-', '_'));
        String teamId = team.get("id").asString();
        String newUsername = unique("member-");
        String newUserId = createUser(admin, newUsername, List.of("ENGINEER")).get("id").asString();

        mvc.perform(jsonRequest(post("/api/v1/teams/" + teamId + "/members"), coordinator,
                        Map.of("userId", newUserId, "memberType", "ON_CALL", "isPrimary", true, "teamRole", "sre")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.members", hasSize(1)))
                .andExpect(jsonPath("$.members[0].userId").value(newUserId))
                .andExpect(jsonPath("$.members[0].username").value(newUsername))
                .andExpect(jsonPath("$.members[0].memberType").value("ON_CALL"))
                .andExpect(jsonPath("$.members[0].isPrimary").value(true))
                .andExpect(jsonPath("$.members[0].teamRole").value("sre"))
                .andExpect(jsonPath("$.members[0].joinedAt").isNotEmpty());

        mvc.perform(jsonRequest(post("/api/v1/teams/" + teamId + "/members"), coordinator,
                        Map.of("userId", newUserId, "memberType", "PRIMARY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEAM_MEMBER_EXISTS"));
        mvc.perform(jsonRequest(post("/api/v1/teams/" + teamId + "/members"), coordinator,
                        Map.of("userId", UUID.randomUUID().toString(), "memberType", "PRIMARY")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mvc.perform(jsonRequest(post("/api/v1/teams/" + UUID.randomUUID() + "/members"), coordinator,
                        Map.of("userId", newUserId, "memberType", "PRIMARY")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));

        // the membership is visible to the user through the identity port (GET /auth/me)
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(tokenOf(newUsername, NEW_USER_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teams[*].id", hasItem(teamId)))
                .andExpect(jsonPath("$.teams[?(@.id == '" + teamId + "')].memberType").value("ON_CALL"));
        mvc.perform(get("/api/v1/users/" + newUserId).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.teams[0].code").value(team.get("code").asString()));

        mvc.perform(delete("/api/v1/teams/" + teamId + "/members/" + newUserId).header("Authorization", bearer(coordinator)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/teams/" + teamId + "/members/" + newUserId).header("Authorization", bearer(coordinator)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEAM_MEMBER_NOT_FOUND"));
        mvc.perform(get("/api/v1/teams/" + teamId).header("Authorization", bearer(coordinator)))
                .andExpect(jsonPath("$.members").isEmpty());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(tokenOf(newUsername, NEW_USER_PASSWORD))))
                .andExpect(jsonPath("$.teams[*].id", not(hasItem(teamId))));

        assertThat(jdbc.queryForObject("select count(*) from team_members where team_id = ?::uuid", Integer.class, teamId)).isZero();
        List<String> actions = jdbc.queryForList(
                "select action from audit_logs where resource_type = 'Team' and resource_id = ?::uuid order by created_at",
                String.class, teamId);
        assertThat(actions).containsExactly("TEAM_CREATED", "TEAM_MEMBER_ADDED", "TEAM_MEMBER_REMOVED");
    }

    @Test
    void engineer_cannotManageMembers_evenOfTheirOwnTeam() throws Exception {
        mvc.perform(jsonRequest(post("/api/v1/teams/" + TEAM_PAYMENT_ID + "/members"), engineerToken(),
                        Map.of("userId", ENGINEER_B_ID.toString(), "memberType", "PRIMARY")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));
        mvc.perform(delete("/api/v1/teams/" + TEAM_PAYMENT_ID + "/members/" + COORDINATOR_ID)
                        .header("Authorization", bearer(engineerToken())))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from team_members where team_id = ?", Integer.class, TEAM_PAYMENT_ID))
                .isEqualTo(2);
    }
}
