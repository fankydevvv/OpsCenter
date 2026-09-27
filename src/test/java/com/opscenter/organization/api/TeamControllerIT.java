package com.opscenter.organization.api;

import java.util.Map;
import java.util.UUID;

import com.opscenter.identity.testsupport.IdentityIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teams end to end (04-API §5, §21; TC-IDEMP-001 adapted): paginated list with member counts,
 * idempotent create, detail with member names, PATCH with optimistic locking and the
 * {@code INACTIVE} soft delete.
 */
class TeamControllerIT extends IdentityIntegrationTest {

    @Test
    void list_showsSeededTeamsWithMemberCounts_andFilters() throws Exception {
        String engineer = engineerToken();

        // seeded counts are lower bounds: other tests may add members to the seeded teams
        mvc.perform(get("/api/v1/teams?size=50").header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].code", hasItems("PAYMENT", "PLATFORM")))
                .andExpect(jsonPath("$.items[?(@.code == 'PAYMENT')].memberCount", hasItem(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.items[?(@.code == 'PLATFORM')].memberCount", hasItem(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.size").value(50));
        mvc.perform(get("/api/v1/teams?q=platf").header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].code", hasItem("PLATFORM")))
                .andExpect(jsonPath("$.items[?(@.code == 'PLATFORM')].teamType", hasItem("DEVOPS")));
        // LIKE wildcards are literal in q (escaped); sort is whitelisted
        mvc.perform(get("/api/v1/teams?q=%25").header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/teams?sort=organizationId").header("Authorization", bearer(engineer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/teams?organizationId=" + UUID.randomUUID()).header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void TC_IDEMP_001_createTwiceWithTheSameKey_createsOneTeam() throws Exception {
        String coordinator = coordinatorToken();
        // codes are normalised to upper case by the domain (Team.normalizeCode)
        String code = unique("IDEM").toUpperCase();
        Map<String, Object> request = Map.of("code", code.toLowerCase(), "name", "Idempotent team", "teamType", "qa");
        String key = "team-" + code;

        MvcResult first = mvc.perform(jsonRequest(post("/api/v1/teams"), coordinator, request).header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.teamType").value("QA"))
                .andExpect(jsonPath("$.organizationId").value(DEFAULT_ORG_ID.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.onCallEnabled").value(false))
                .andExpect(jsonPath("$.members").isEmpty())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        String id = body(first).get("id").asString();

        mvc.perform(jsonRequest(post("/api/v1/teams"), coordinator, request).header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        assertThat(jdbc.queryForObject("select count(*) from teams where code = ?", Integer.class, code)).isEqualTo(1);

        mvc.perform(jsonRequest(post("/api/v1/teams"), coordinator, Map.of("code", code + "2", "name", "Other"))
                        .header("Idempotency-Key", key))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(jsonRequest(post("/api/v1/teams"), coordinator, request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEAM_CODE_TAKEN"));
        mvc.perform(jsonRequest(post("/api/v1/teams"), coordinator,
                        Map.of("code", code + "3", "name", "Other", "organizationId", UUID.randomUUID().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_FOUND"));
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'TEAM_CREATED' and resource_id = ?::uuid and actor_id = ?",
                Integer.class, id, COORDINATOR_ID)).isEqualTo(1);
    }

    @Test
    void get_returnsMembersWithNamesFromIdentity() throws Exception {
        mvc.perform(get("/api/v1/teams/" + TEAM_PAYMENT_ID).header("Authorization", bearer(engineerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PAYMENT"))
                .andExpect(jsonPath("$.members", hasSize(2)))
                .andExpect(jsonPath("$.members[?(@.username == 'engineer.a')].memberType").value("PRIMARY"))
                .andExpect(jsonPath("$.members[?(@.username == 'engineer.a')].isPrimary").value(true))
                .andExpect(jsonPath("$.members[?(@.username == 'coordinator')].memberType").value("SECONDARY"))
                .andExpect(jsonPath("$.members[?(@.username == 'coordinator')].displayName").value("Coordinator A"));
        mvc.perform(get("/api/v1/teams/" + UUID.randomUUID()).header("Authorization", bearer(engineerToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
    }

    @Test
    void patch_withOptimisticLocking_andInactiveAsSoftDelete() throws Exception {
        String coordinator = coordinatorToken();
        JsonNode team = createTeam(coordinator, unique("PATCH").replace('-', '_'));
        String id = team.get("id").asString();

        mvc.perform(jsonRequest(patch("/api/v1/teams/" + id), coordinator,
                        Map.of("name", "Renamed", "onCallEnabled", true, "version", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.onCallEnabled").value(true))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(jsonRequest(patch("/api/v1/teams/" + id), coordinator, Map.of("name", "Stale", "version", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENCY_VERSION_CONFLICT"));
        mvc.perform(jsonRequest(patch("/api/v1/teams/" + id), coordinator, Map.of("name", "X")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));

        mvc.perform(jsonRequest(patch("/api/v1/teams/" + id), coordinator, Map.of("status", "INACTIVE", "version", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        Map<String, Object> row = jdbc.queryForMap("select is_active, deleted_at from teams where id = ?::uuid", id);
        assertThat(row.get("is_active")).isEqualTo(false);
        assertThat(row.get("deleted_at")).isNotNull();
        mvc.perform(jsonRequest(post("/api/v1/teams/" + id + "/members"), coordinator,
                        Map.of("userId", ENGINEER_B_ID.toString(), "memberType", "PRIMARY")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TEAM_INACTIVE"));
        // the engineer cannot update teams at all
        mvc.perform(jsonRequest(patch("/api/v1/teams/" + id), engineerToken(), Map.of("name", "Nope", "version", 2)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'TEAM_UPDATED' and resource_id = ?::uuid", Integer.class, id))
                .isEqualTo(2);
    }
}
