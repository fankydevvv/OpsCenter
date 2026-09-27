package com.opscenter.shared.infrastructure.status;

import com.opscenter.identity.testsupport.IdentityIntegrationTest;
import com.opscenter.support.RabbitTestcontainersConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The RabbitMQ half of D-62/D-63 in the context that has a broker (shared with {@code OutboxRelayIT}):
 * the status page reports the broker version and the outbox backlog, {@code /actuator/health}
 * shows a {@code rabbit} component, and with every dependency up the platform is UP except for the
 * monitoring tools that tests do not run (UNKNOWN is neutral).
 */
@Import(RabbitTestcontainersConfiguration.class)
class RabbitConnectivityIT extends IdentityIntegrationTest {

    @Test
    void systemStatus_reportsRabbitVersionAndOutboxBacklog_andOverallUp() throws Exception {
        mvc.perform(get("/api/v1/system/status").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("UP"))
                .andExpect(jsonPath("$.components[3].name").value("rabbitmq"))
                .andExpect(jsonPath("$.components[3].status").value("UP"))
                .andExpect(jsonPath("$.components[3].version").value(startsWith("4.")))
                .andExpect(jsonPath("$.components[3].details.outboxPending").isNumber())
                .andExpect(jsonPath("$.components[3].details.outboxFailed").isNumber());
    }

    @Test
    void actuatorHealth_showsTheRabbitComponentToAnAdministrator() throws Exception {
        mvc.perform(get("/actuator/health").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.rabbit.status").value("UP"))
                .andExpect(jsonPath("$.components.rabbit.details.version").value(startsWith("4.")));
    }
}
