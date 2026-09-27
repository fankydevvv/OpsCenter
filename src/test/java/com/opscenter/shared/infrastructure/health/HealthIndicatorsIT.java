package com.opscenter.shared.infrastructure.health;

import com.opscenter.identity.testsupport.IdentityIntegrationTest;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Blueprint D-62 on the full application with Testcontainers PostgreSQL, Redis and MinIO:
 * {@code /actuator/health} shows {@code db}, {@code redis} and the custom {@code minio} component to
 * an administrator only, and the readiness group contains nothing but {@code readinessState} and
 * {@code db} - an outage of Redis/MinIO/RabbitMQ must never make the container "not ready".
 */
class HealthIndicatorsIT extends IdentityIntegrationTest {

    @Test
    void administratorSeesDbRedisAndMinio() throws Exception {
        mvc.perform(get("/actuator/health").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.details.version").isNotEmpty())
                .andExpect(jsonPath("$.components.minio.status").value("UP"))
                .andExpect(jsonPath("$.components.minio.details.bucket").value("opscenter-raw"))
                // the main test context has no broker, so the test profile disables the rabbit indicator
                .andExpect(jsonPath("$.components.rabbit").doesNotExist());
    }

    @Test
    void anonymousAndNonAdminCallers_seeTheStatusOnly() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health").header("Authorization", bearer(engineerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void readinessGroup_isReadinessStateAndDbOnly() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.redis").doesNotExist())
                .andExpect(jsonPath("$.components.minio").doesNotExist());
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
