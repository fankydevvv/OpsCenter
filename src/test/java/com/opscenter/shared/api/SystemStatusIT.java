package com.opscenter.shared.api;

import com.opscenter.identity.testsupport.IdentityIntegrationTest;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/system/status} end to end (D-63) with real PostgreSQL, Redis and MinIO
 * containers. In this context there is deliberately no RabbitMQ (the test profile points the
 * client at a refused port) and no Prometheus/Alertmanager URL - so the endpoint must report
 * rabbitmq DOWN, the two monitoring tools UNKNOWN and the platform DEGRADED, still with HTTP 200.
 */
class SystemStatusIT extends IdentityIntegrationTest {

    @Test
    void admin_seesEveryComponent_withVersionsLatencyAndOverallDegraded() throws Exception {
        mvc.perform(get("/api/v1/system/status").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedAt").isNotEmpty())
                .andExpect(jsonPath("$.overallStatus").value("DEGRADED"))
                .andExpect(jsonPath("$.components.length()").value(7))
                .andExpect(jsonPath("$.components[0].name").value("backend"))
                .andExpect(jsonPath("$.components[0].status").value("UP"))
                .andExpect(jsonPath("$.components[0].details.profiles[0]").value("test"))
                .andExpect(jsonPath("$.components[1].name").value("postgres"))
                .andExpect(jsonPath("$.components[1].status").value("UP"))
                .andExpect(jsonPath("$.components[1].version").value(startsWith("17")))
                .andExpect(jsonPath("$.components[1].details.flywayVersion").isNotEmpty())
                .andExpect(jsonPath("$.components[1].latencyMs").isNumber())
                .andExpect(jsonPath("$.components[2].name").value("redis"))
                .andExpect(jsonPath("$.components[2].status").value("UP"))
                .andExpect(jsonPath("$.components[2].version").value(startsWith("7.")))
                .andExpect(jsonPath("$.components[3].name").value("rabbitmq"))
                .andExpect(jsonPath("$.components[3].status").value("DOWN"))
                .andExpect(jsonPath("$.components[3].error").isNotEmpty())
                .andExpect(jsonPath("$.components[3].error").value(not(containsString("guest:guest"))))
                .andExpect(jsonPath("$.components[3].details.outboxPending").isNumber())
                .andExpect(jsonPath("$.components[4].name").value("minio"))
                .andExpect(jsonPath("$.components[4].status").value("UP"))
                .andExpect(jsonPath("$.components[4].details.bucket").value("opscenter-raw"))
                .andExpect(jsonPath("$.components[5].name").value("prometheus"))
                .andExpect(jsonPath("$.components[5].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.components[6].name").value("alertmanager"))
                .andExpect(jsonPath("$.components[6].status").value("UNKNOWN"));
    }

    @Test
    void withoutSystemRead_is403_andAnonymousIs401() throws Exception {
        mvc.perform(get("/api/v1/system/status").header("Authorization", bearer(engineerToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));
        mvc.perform(get("/api/v1/system/status").header("Authorization", bearer(coordinatorToken())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isUnauthorized());
    }
}
