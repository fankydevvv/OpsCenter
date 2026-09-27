package com.opscenter.organization.api;

import java.util.Map;
import java.util.UUID;

import com.opscenter.identity.testsupport.IdentityIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Organizations end to end (03-DB §6.1): readable by every role, updatable by admins only, with locking. */
class OrganizationControllerIT extends IdentityIntegrationTest {

    @Test
    void list_get_andUpdateWithOptimisticLocking() throws Exception {
        String admin = adminToken();

        mvc.perform(get("/api/v1/organizations").header("Authorization", bearer(engineerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItem("DEFAULT")));
        MvcResult current = mvc.perform(get("/api/v1/organizations/" + DEFAULT_ORG_ID).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("DEFAULT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        long version = body(current).get("version").asLong();

        mvc.perform(jsonRequest(patch("/api/v1/organizations/" + DEFAULT_ORG_ID), engineerToken(),
                        Map.of("name", "Nope", "version", version)))
                .andExpect(status().isForbidden());
        mvc.perform(jsonRequest(patch("/api/v1/organizations/" + DEFAULT_ORG_ID), admin,
                        Map.of("name", "OpsCenter Default Organization (renamed)", "version", version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("OpsCenter Default Organization (renamed)"))
                .andExpect(jsonPath("$.version").value(version + 1));
        mvc.perform(jsonRequest(patch("/api/v1/organizations/" + DEFAULT_ORG_ID), admin,
                        Map.of("name", "Stale", "version", version)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENCY_VERSION_CONFLICT"));
        // restore the seeded name for the other tests (second update -> version + 2)
        mvc.perform(jsonRequest(patch("/api/v1/organizations/" + DEFAULT_ORG_ID), admin,
                        Map.of("name", "OpsCenter Default Organization", "version", version + 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version + 2));
        mvc.perform(get("/api/v1/organizations/" + UUID.randomUUID()).header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_NOT_FOUND"));

        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'ORGANIZATION_UPDATED' and resource_id = ?", Integer.class,
                DEFAULT_ORG_ID)).isPositive();
    }
}
