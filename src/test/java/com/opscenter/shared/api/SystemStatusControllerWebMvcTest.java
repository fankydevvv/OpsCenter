package com.opscenter.shared.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.shared.application.status.ComponentState;
import com.opscenter.shared.application.status.ComponentStatus;
import com.opscenter.shared.application.status.OverallStatus;
import com.opscenter.shared.application.status.SystemStatusService;
import com.opscenter.shared.application.status.SystemStatusView;
import com.opscenter.support.SecuritySliceConfig;
import com.opscenter.support.TestJwts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D-63: {@code GET /api/v1/system/status} needs a token (401) and {@code system.read} (403). */
@WebMvcTest(controllers = SystemStatusController.class)
@Import(SecuritySliceConfig.class)
@ActiveProfiles("test")
class SystemStatusControllerWebMvcTest {

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder jwtEncoder;
    @MockitoBean SystemStatusService systemStatus;

    private String tokenWith(List<String> permissions) {
        return TestJwts.issue(jwtEncoder, UUID.randomUUID(), UUID.randomUUID(), "someone", List.of("X"), permissions,
                Instant.now(), Duration.ofMinutes(30));
    }

    @Test
    void anonymousIs401_andWithoutSystemReadIs403() throws Exception {
        mvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/system/status")
                        .header("Authorization", "Bearer " + tokenWith(List.of("service.read", "team.read"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));
        verifyNoInteractions(systemStatus);
    }

    @Test
    void withSystemRead_returnsTheComponents() throws Exception {
        when(systemStatus.check()).thenReturn(new SystemStatusView(Instant.parse("2026-09-27T10:00:00Z"),
                OverallStatus.DEGRADED, List.of(
                new ComponentStatus("postgres", ComponentState.UP, "17.6", 3L, Map.of("flywayVersion", "7.1"), null),
                new ComponentStatus("redis", ComponentState.DOWN, null, 2000L, Map.of(), "No answer within 2000 ms"))));

        mvc.perform(get("/api/v1/system/status").header("Authorization", "Bearer " + tokenWith(List.of("system.read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedAt").value("2026-09-27T10:00:00Z"))
                .andExpect(jsonPath("$.overallStatus").value("DEGRADED"))
                .andExpect(jsonPath("$.components[0].name").value("postgres"))
                .andExpect(jsonPath("$.components[0].version").value("17.6"))
                .andExpect(jsonPath("$.components[0].details.flywayVersion").value("7.1"))
                .andExpect(jsonPath("$.components[1].status").value("DOWN"))
                .andExpect(jsonPath("$.components[1].error").value("No answer within 2000 ms"));
    }
}
