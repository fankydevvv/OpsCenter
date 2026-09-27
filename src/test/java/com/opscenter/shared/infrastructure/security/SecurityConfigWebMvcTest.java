package com.opscenter.shared.infrastructure.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.support.ProbeController;
import com.opscenter.support.SecuritySliceConfig;
import com.opscenter.support.TestJwts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-16 / 04-API §18: stateless bearer security - public paths open, everything else needs a valid
 * token, permissions come from JWT claims, revoked sessions and bad tokens are rejected with the
 * right error code (TC-AUTH-005 semantics for the shared part, TC-RBAC-001/003 for enforcement).
 */
@WebMvcTest(controllers = ProbeController.class)
@Import(SecuritySliceConfig.class)
@ActiveProfiles({"test", "probe"})
class SecurityConfigWebMvcTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Test
    void publicLoginPathNeedsNoToken() throws Exception {
        mvc.perform(post("/api/v1/auth/login"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.probe").value("login"));
    }

    @Test
    void publicActuatorAndOpenApiPathsAreNotChallenged() throws Exception {
        // No actuator/springdoc in the slice: 404 proves the request passed security (not 401).
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/info",
                "/actuator/prometheus", "/v3/api-docs", "/swagger-ui/index.html")) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void missingToken_returns401WithContractBodyAndRequestId() throws Exception {
        mvc.perform(get("/api/v1/probe/me").header("X-Request-Id", "sec-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "sec-1"))
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").value("sec-1"));
    }

    @Test
    void validToken_exposesPermissionsAndRolesAsAuthorities() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String token = TestJwts.admin(jwtEncoder, userId, sessionId);

        mvc.perform(get("/api/v1/probe/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("admin"))
                .andExpect(jsonPath("$.authorities", hasItems("user.read", "team.member.manage", "ROLE_ADMIN")))
                .andExpect(jsonPath("$.actorId").value(userId.toString()))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void TC_RBAC_001_engineerCallingUserAdminEndpoint_returns403() throws Exception {
        String token = TestJwts.engineer(jwtEncoder, UUID.randomUUID(), UUID.randomUUID());

        mvc.perform(get("/api/v1/probe/users").header("Authorization", "Bearer " + token).header("X-Request-Id", "rbac-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"))
                .andExpect(jsonPath("$.requestId").value("rbac-1"));
        mvc.perform(get("/api/v1/probe/admin-role").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void TC_RBAC_003_permissionIsEnforcedByBackendForDirectApiCalls() throws Exception {
        String token = TestJwts.admin(jwtEncoder, UUID.randomUUID(), UUID.randomUUID());

        mvc.perform(get("/api/v1/probe/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mvc.perform(get("/api/v1/probe/admin-role").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void expiredToken_returns401Unauthenticated() throws Exception {
        String token = TestJwts.issue(jwtEncoder, UUID.randomUUID(), UUID.randomUUID(), "admin", List.of("ADMIN"),
                TestJwts.ALL_BASE_PERMISSIONS, Instant.now().minus(Duration.ofHours(2)), Duration.ofMinutes(30));

        mvc.perform(get("/api/v1/probe/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test
    void tamperedSignature_returns401Unauthenticated() throws Exception {
        String token = TestJwts.admin(jwtEncoder, UUID.randomUUID(), UUID.randomUUID());
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        mvc.perform(get("/api/v1/probe/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test
    void wrongIssuer_returns401Unauthenticated() throws Exception {
        String token = TestJwts.issue(jwtEncoder, "someone-else", UUID.randomUUID(), UUID.randomUUID(), "admin",
                List.of("ADMIN"), TestJwts.ALL_BASE_PERMISSIONS, Instant.now(), Duration.ofMinutes(30));

        mvc.perform(get("/api/v1/probe/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test
    void TC_AUTH_005_revokedSession_returns401SessionRevoked() throws Exception {
        String token = TestJwts.admin(jwtEncoder, UUID.randomUUID(), SecuritySliceConfig.REVOKED_SESSION);

        mvc.perform(get("/api/v1/probe/me").header("Authorization", "Bearer " + token).header("X-Request-Id", "auth-5"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_REVOKED"))
                .andExpect(jsonPath("$.requestId").value("auth-5"));
    }
}
