package com.opscenter.shared.infrastructure.web;

import java.util.UUID;

import com.opscenter.support.LogCapture;
import com.opscenter.support.ProbeController;
import com.opscenter.support.SecuritySliceConfig;
import com.opscenter.support.TestJwts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 07-TC §22: every error status carries {@code requestId} and a stable {@code code}, in the
 * 04-API §2.3 body, whether it is produced by the controller advice or by the security handlers.
 */
@WebMvcTest(controllers = ProbeController.class)
@Import(SecuritySliceConfig.class)
@ActiveProfiles({"test", "probe"})
class ApiExceptionHandlerWebMvcTest {

    private static final String REQ = "tc-api-errors";
    private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtEncoder jwtEncoder;

    String bearer;

    @BeforeEach
    void mintToken() {
        bearer = "Bearer " + TestJwts.admin(jwtEncoder, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void validationFailure_returns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/probe/validate")
                        .header("Authorization", bearer).header("X-Request-Id", REQ)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", REQ))
                .andExpect(jsonPath("$.requestId").value(REQ))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern(ISO_INSTANT)))
                .andExpect(jsonPath("$.fieldErrors", hasSize(2)))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'email')]").exists());
    }

    /**
     * 04-API §18 / 05-DEPLOY §16: Spring's own message for a validation failure embeds the
     * rejected values ("rejected value [...]"); the handler must log field names only.
     */
    @Test
    void validationFailure_logsFieldNamesButNeverTheRejectedValues() throws Exception {
        String secret = "Sup3r-Secret-Value";
        try (LogCapture logs = LogCapture.of(ApiExceptionHandler.class)) {
            mvc.perform(post("/api/v1/probe/validate")
                            .header("Authorization", bearer).header("X-Request-Id", REQ)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\",\"email\":\"" + secret + "\"}"))
                    .andExpect(status().isBadRequest());

            assertThat(logs.messages()).isNotEmpty();
            assertThat(String.join("\n", logs.messages()))
                    .contains("VALIDATION_FAILED").contains("email")
                    .doesNotContain(secret).doesNotContain("rejected value");
        }
    }

    @Test
    void malformedJson_returns400RequestMalformed() throws Exception {
        mvc.perform(post("/api/v1/probe/validate")
                        .header("Authorization", bearer).header("X-Request-Id", REQ)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"))
                .andExpect(jsonPath("$.requestId").value(REQ));
    }

    @Test
    void missingToken_returns401FromEntryPoint() throws Exception {
        mvc.perform(get("/api/v1/probe/users").header("X-Request-Id", REQ))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", REQ))
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").value(REQ))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void missingPermission_returns403FromMethodSecurity() throws Exception {
        String engineer = "Bearer " + TestJwts.engineer(jwtEncoder, UUID.randomUUID(), UUID.randomUUID());
        mvc.perform(get("/api/v1/probe/users").header("Authorization", engineer).header("X-Request-Id", REQ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"))
                .andExpect(jsonPath("$.requestId").value(REQ))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void domainNotFound_returns404WithModuleCode() throws Exception {
        mvc.perform(get("/api/v1/probe/errors/not-found").header("Authorization", bearer).header("X-Request-Id", REQ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("User does not exist"))
                .andExpect(jsonPath("$.requestId").value(REQ));
    }

    @Test
    void unknownPath_returns404ResourceNotFound() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist").header("Authorization", bearer).header("X-Request-Id", REQ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").value(REQ));
    }

    @Test
    void domainConflict_returns409() throws Exception {
        mvc.perform(get("/api/v1/probe/errors/conflict").header("Authorization", bearer).header("X-Request-Id", REQ))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEAM_CODE_TAKEN"))
                .andExpect(jsonPath("$.requestId").value(REQ));
    }

    @Test
    void optimisticLock_returns409VersionConflict() throws Exception {
        mvc.perform(get("/api/v1/probe/errors/optimistic").header("Authorization", bearer).header("X-Request-Id", REQ))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENCY_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.requestId").value(REQ));
    }

    @Test
    void businessRule_returns422() throws Exception {
        mvc.perform(get("/api/v1/probe/errors/business").header("Authorization", bearer).header("X-Request-Id", REQ))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("USER_CANNOT_LOCK_SELF"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.requestId").value(REQ));
    }

    @Test
    void methodNotAllowed_returns405() throws Exception {
        mvc.perform(post("/api/v1/probe/users").header("Authorization", bearer).header("X-Request-Id", REQ))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.requestId").value(REQ));
    }

    /** 04-API §17 "503 Dependency unavailable" / 07-TC §22: a dead database is not a 500. */
    @Test
    void dependencyUnavailable_returns503() throws Exception {
        mvc.perform(get("/api/v1/probe/errors/unavailable").header("Authorization", bearer).header("X-Request-Id", REQ))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.requestId").value(REQ));
    }

    @Test
    void unexpectedException_returns500WithoutLeakingDetails() throws Exception {
        mvc.perform(get("/api/v1/probe/errors/boom").header("Authorization", bearer).header("X-Request-Id", REQ))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").value(REQ))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("must not leak"))));
    }

    @Test
    void pageableIsBoundWithDefaultsAndCappedAt100() throws Exception {
        mvc.perform(get("/api/v1/probe/page").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(250))
                .andExpect(jsonPath("$.totalPages").value(13));

        mvc.perform(get("/api/v1/probe/page?page=1&size=500").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(100));
    }
}
