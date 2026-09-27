package com.opscenter.identity.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 07-TC §6 authentication cases end to end on the seeded database (V005 + dev seed), plus
 * TC-AUD-001, the D-09 rate limit and the D-29 concurrency guarantees. Each test uses either a
 * seeded account or a user it creates itself, so the cases are independent and need no cleanup.
 */
class AuthControllerIT extends IdentityIntegrationTest {

    @Test
    void TC_AUTH_001_validLogin_createsSessionAndTokens_andIsAudited() throws Exception {
        JsonNode body = login("admin", ADMIN_PASSWORD);

        assertThat(body.get("accessToken").asString()).isNotBlank();
        assertThat(body.get("refreshToken").asString()).isNotBlank();
        assertThat(body.get("expiresIn").asLong()).isEqualTo(1800);
        assertThat(body.get("user").get("id").asString()).isEqualTo(ADMIN_ID.toString());
        assertThat(body.get("user").get("roles").get(0).asString()).isEqualTo("ADMIN");
        // 15 base permissions (blueprint §6.2) + user.delete (V006, D-26)
        assertThat(body.get("user").get("permissions").size()).isEqualTo(16);

        Integer activeSessions = jdbc.queryForObject(
                "select count(*) from user_sessions where user_id = ? and revoked_at is null", Integer.class, ADMIN_ID);
        assertThat(activeSessions).isPositive();
        Integer successAttempts = jdbc.queryForObject(
                "select count(*) from login_attempts where user_id = ? and success", Integer.class, ADMIN_ID);
        assertThat(successAttempts).isPositive();
        assertThat(jdbc.queryForObject("select last_login_at from users where id = ?", Object.class, ADMIN_ID)).isNotNull();
        // TC-AUD-001: login success is audited with the user as actor
        Integer audited = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'AUTH_LOGIN_SUCCESS' and actor_id = ? and resource_id = ?",
                Integer.class, ADMIN_ID, ADMIN_ID);
        assertThat(audited).isPositive();
    }

    @Test
    void TC_AUTH_001_loginByEmail_worksCaseInsensitively() throws Exception {
        JsonNode body = login("Engineer.A@OpsCenter.local", ENGINEER_PASSWORD);
        assertThat(body.get("user").get("username").asString()).isEqualTo("engineer.a");
        assertThat(body.get("user").get("permissions")).extracting(JsonNode::asString)
                .containsExactly("organization.read", "team.read");
    }

    @Test
    void TC_AUTH_002_wrongPassword_returnsGeneric401_andRecordsFailedAttempt() throws Exception {
        String username = unique("auth2-");
        UUID userId = UUID.fromString(createUser(adminToken(), username, List.of("ENGINEER")).get("id").asString());

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "tc-auth-002")
                        .content(json.writeValueAsString(Map.of("login", username, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.requestId").value("tc-auth-002"))
                .andExpect(jsonPath("$.message").value("Invalid username/email or password"))
                .andExpect(header().string("X-Request-Id", "tc-auth-002"));

        // An unknown login gets the very same answer: the response must not reveal which part failed.
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("login", "nobody-" + username, "password", "x"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid username/email or password"));

        // D-29 "throw after commit": the failure rows survive although the request answered 401
        String reason = jdbc.queryForObject(
                "select failure_reason from login_attempts where user_id = ? and not success", String.class, userId);
        assertThat(reason).isEqualTo("BAD_CREDENTIALS");
        Integer sessions = jdbc.queryForObject("select count(*) from user_sessions where user_id = ?", Integer.class, userId);
        assertThat(sessions).isZero();
        Integer audited = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'AUTH_LOGIN_FAILED' and actor_id = ?", Integer.class, userId);
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void TC_AUTH_003_disabledAccount_isRefusedWithContractCode_andNoSessionIsCreated() throws Exception {
        String admin = adminToken();
        String username = unique("auth3-");
        UUID userId = UUID.fromString(createUser(admin, username, List.of("ENGINEER")).get("id").asString());
        mvc.perform(delete("/api/v1/users/" + userId).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("login", username, "password", NEW_USER_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_DISABLED"));

        Integer sessions = jdbc.queryForObject("select count(*) from user_sessions where user_id = ?", Integer.class, userId);
        assertThat(sessions).isZero();
        assertThat(jdbc.queryForObject("select failure_reason from login_attempts where user_id = ?", String.class, userId))
                .isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    void TC_AUTH_003_lockedAccount_isRefusedWithContractCode() throws Exception {
        String admin = adminToken();
        String username = unique("auth3l-");
        UUID userId = UUID.fromString(createUser(admin, username, List.of("ENGINEER")).get("id").asString());
        mvc.perform(jsonRequest(post("/api/v1/users/" + userId + "/lock"), admin, Map.of("reason", "test")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("login", username, "password", NEW_USER_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_LOCKED"));
        // wrong password on a locked account still answers the generic code (D-09)
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("login", username, "password", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    void TC_AUTH_004_refresh_rotatesTokens_andReuseOfTheOldTokenRevokesTheSession() throws Exception {
        String username = unique("auth4-");
        createUser(adminToken(), username, List.of("ENGINEER"));
        JsonNode first = login(username, NEW_USER_PASSWORD);
        String firstRefresh = first.get("refreshToken").asString();

        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", firstRefresh))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andReturn();
        JsonNode second = body(refreshed);
        assertThat(second.get("accessToken").asString()).isNotEqualTo(first.get("accessToken").asString());
        assertThat(second.get("refreshToken").asString()).isNotEqualTo(firstRefresh);

        // the old token is revoked and linked to its successor
        Map<String, Object> chain = jdbc.queryForMap(
                "select revoked_at, replaced_by_id from refresh_tokens t join users u on u.id = t.user_id "
                        + "where u.username = ? order by t.created_at asc limit 1", username);
        assertThat(chain.get("revoked_at")).isNotNull();
        assertThat(chain.get("replaced_by_id")).isNotNull();

        // new access token works
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(second.get("accessToken").asString())))
                .andExpect(status().isOk());

        // replaying the consumed token = reuse -> 401 and the whole session dies
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", firstRefresh))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(second.get("accessToken").asString())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_REVOKED"));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", second.get("refreshToken").asString()))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * D-29: the presented token row is locked, so concurrent refreshes with the same token are
     * serialised. Exactly one wins; the others see the rotated row, treat it as reuse (D-02) and
     * kill the session - so the winner's brand-new tokens are dead as well.
     */
    @Test
    void D29_concurrentRefreshesWithTheSameToken_rotateExactlyOnce_andRevokeTheSession() throws Exception {
        String username = unique("auth4p-");
        createUser(adminToken(), username, List.of("ENGINEER"));
        String refreshToken = login(username, NEW_USER_PASSWORD).get("refreshToken").asString();
        String body = json.writeValueAsString(Map.of("refreshToken", refreshToken));

        List<MvcResult> results = inParallel(4, () -> mvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn());

        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
        assertThat(statuses).containsOnly(200, 401);
        assertThat(statuses.stream().filter(s -> s == 200).count()).as("exactly one rotation %s", statuses).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from refresh_tokens t join users u on u.id = t.user_id where u.username = ? "
                        + "and t.revoked_at is null", Integer.class, username))
                .as("no live refresh token survives a detected reuse").isZero();

        MvcResult winner = results.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
        String winnerAccess = json.readTree(winner.getResponse().getContentAsString()).get("accessToken").asString();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(winnerAccess)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_REVOKED"));
    }

    /**
     * D-29: a failed login must cost one pooled connection, not two. With the earlier
     * {@code REQUIRES_NEW} nesting, more concurrent bad passwords than the Hikari pool size (10)
     * dead-locked the pool until the connection timeout and every request answered 500.
     */
    @Test
    void D29_concurrentFailedLogins_beyondThePoolSize_allAnswer401() throws Exception {
        List<MvcResult> results = inParallel(20, () -> {
            String login = unique("flood-");
            return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("login", login, "password", "wrong")))).andReturn();
        });

        assertThat(results).extracting(r -> r.getResponse().getStatus()).containsOnly(401);
    }

    @Test
    void TC_AUTH_005_logout_revokesTheSession_andLaterRequestsAreRejected() throws Exception {
        JsonNode session = login("engineer.b", ENGINEER_PASSWORD);
        String access = session.get("accessToken").asString();

        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(access)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("engineer.b"));

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(access)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(access)).header("X-Request-Id", "tc-auth-005"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_REVOKED"))
                .andExpect(jsonPath("$.requestId").value("tc-auth-005"));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", session.get("refreshToken").asString()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));

        Integer audited = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'AUTH_LOGOUT' and actor_id = ?", Integer.class, ENGINEER_B_ID);
        assertThat(audited).isPositive();
    }

    @Test
    void me_returnsProfileRolesPermissionsAndTeams() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(engineerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ENGINEER_A_ID.toString()))
                .andExpect(jsonPath("$.username").value("engineer.a"))
                .andExpect(jsonPath("$.email").value("engineer.a@opscenter.local"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("ENGINEER")))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("organization.read", "team.read")))
                .andExpect(jsonPath("$.permissions", not(hasItem("user.read"))))
                .andExpect(jsonPath("$.teams[0].code").value("PAYMENT"))
                .andExpect(jsonPath("$.teams[0].memberType").value("PRIMARY"));
    }

    @Test
    void D09_fiveFailedAttempts_blockTheSixthWith429() throws Exception {
        String login = unique("ratelimit-");
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("login", login, "password", "wrong"))))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "tc-429")
                        .content(json.writeValueAsString(Map.of("login", login, "password", "wrong"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_TOO_MANY_ATTEMPTS"))
                .andExpect(jsonPath("$.requestId").value("tc-429"));
    }

    @Test
    void login_withMissingFields_returns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"login\":\"admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    }

    @Test
    void refresh_withAnOversizedToken_isRejectedBeforeAnyLookup() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", "x".repeat(129)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("refreshToken"));
    }

    // --- helpers ----------------------------------------------------------------------------

    private interface MvcCall {
        MvcResult call() throws Exception;
    }

    /** Runs {@code call} on {@code parallelism} threads released by one latch, and collects every result. */
    private static List<MvcResult> inParallel(int parallelism, MvcCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < parallelism; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        }
        finally {
            pool.shutdownNow();
        }
    }
}
