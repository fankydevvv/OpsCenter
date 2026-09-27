package com.opscenter.identity.testsupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.support.AbstractIntegrationTest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class of the identity/organization integration tests: the full application on a
 * Testcontainers PostgreSQL migrated with V001-V005 (so the seeded DEV accounts exist), driven
 * through {@link MockMvc} so the real filter chain, JWT validation and controllers are exercised.
 * <p>
 * Helpers log in through the public endpoint exactly like a client would - tests never mint
 * tokens by hand here, because the point of an IT is to prove the end-to-end flow
 * (login -> token -> protected endpoint) of 07-TC §6/§7. {@code MockMvc} comes from the shared
 * base configuration so all integration tests share one context and one database container.
 */
public abstract class IdentityIntegrationTest extends AbstractIntegrationTest {

    // Seeded by V005 (blueprint §6) - DEV ONLY credentials.
    protected static final UUID ADMIN_ID = UUID.fromString("00000000-0000-4000-8000-000000000021");
    protected static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000022");
    protected static final UUID ENGINEER_A_ID = UUID.fromString("00000000-0000-4000-8000-000000000023");
    protected static final UUID ENGINEER_B_ID = UUID.fromString("00000000-0000-4000-8000-000000000024");
    protected static final UUID ROLE_ADMIN_ID = UUID.fromString("00000000-0000-4000-8000-000000000011");
    protected static final UUID ROLE_ENGINEER_ID = UUID.fromString("00000000-0000-4000-8000-000000000013");
    protected static final UUID DEFAULT_ORG_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    protected static final UUID TEAM_PAYMENT_ID = UUID.fromString("00000000-0000-4000-8000-000000000101");
    protected static final UUID TEAM_PLATFORM_ID = UUID.fromString("00000000-0000-4000-8000-000000000102");

    protected static final String ADMIN_PASSWORD = "Admin@123";
    protected static final String COORDINATOR_PASSWORD = "Coordinator@123";
    protected static final String ENGINEER_PASSWORD = "Engineer@123";
    protected static final String NEW_USER_PASSWORD = "Str0ng-Passw0rd!";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    // --- authentication helpers -------------------------------------------------------------

    protected JsonNode login(String login, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("login", login, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return body(result);
    }

    protected String tokenOf(String login, String password) throws Exception {
        return login(login, password).get("accessToken").asString();
    }

    protected String adminToken() throws Exception {
        return tokenOf("admin", ADMIN_PASSWORD);
    }

    protected String coordinatorToken() throws Exception {
        return tokenOf("coordinator", COORDINATOR_PASSWORD);
    }

    protected String engineerToken() throws Exception {
        return tokenOf("engineer.a", ENGINEER_PASSWORD);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    // --- request helpers --------------------------------------------------------------------

    protected MockHttpServletRequestBuilder jsonRequest(MockHttpServletRequestBuilder builder, String token,
                                                        Object body) {
        builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (token != null) {
            builder.header("Authorization", bearer(token));
        }
        return builder;
    }

    protected JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        return content.isEmpty() ? null : json.readTree(content);
    }

    /** Creates a user through the API as admin; returns the {@code UserDetail} body. */
    protected JsonNode createUser(String adminToken, String username, List<String> roleCodes) throws Exception {
        Map<String, Object> request = Map.of(
                "username", username,
                "email", username + "@opscenter.local",
                "displayName", "Test " + username,
                "password", NEW_USER_PASSWORD,
                "roleCodes", roleCodes);
        MvcResult result = mvc.perform(jsonRequest(post("/api/v1/users"), adminToken, request))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    /** Creates a team through the API; returns the {@code TeamDetail} body. */
    protected JsonNode createTeam(String token, String code) throws Exception {
        Map<String, Object> request = Map.of("code", code, "name", "Team " + code, "teamType", "DEV");
        MvcResult result = mvc.perform(jsonRequest(post("/api/v1/teams"), token, request))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    /** Short unique suffix so tests never collide on unique columns and need no cleanup. */
    protected static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }
}
