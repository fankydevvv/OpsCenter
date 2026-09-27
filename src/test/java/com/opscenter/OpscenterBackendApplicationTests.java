package com.opscenter;

import java.util.List;

import com.opscenter.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test of the whole base on a clean database (05-DEPLOY §20 "migration from scratch",
 * 07-TC §22 error contract, R-01/R-02 mapping risks): the context starts, Flyway applies
 * V001-V006 (plus the dev seed V005.1 in the test profile), Hibernate validates the entity
 * mappings against them, the public endpoints answer and a protected one returns the contract
 * 401 body.
 */
class OpscenterBackendApplicationTests extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    @Test
    void contextLoads_andFlywayAppliedBaseMigrationsInOrder() {
        List<String> scripts = jdbc.queryForList(
                "select script from flyway_schema_history where success order by installed_rank", String.class);
        assertThat(scripts).startsWith(
                "V001__create_identity.sql",
                "V002__create_organization_team.sql",
                "V003__create_audit.sql",
                "V004__create_idempotency_outbox.sql",
                "V005__seed_identity.sql",
                "V005_1__seed_dev_accounts.sql",
                "V006__review_fixes.sql");
        // D-05: the skeleton's V1__init.sql must never be applied (same Flyway version as V001).
        assertThat(scripts).doesNotContain("V1__init.sql");

        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("users", "roles", "permissions", "user_roles", "role_permissions",
                "user_sessions", "refresh_tokens", "login_attempts", "organizations", "teams", "team_members",
                "audit_logs", "idempotency_keys", "outbox_events");
        assertThat(tables).doesNotContain("system_info");
    }

    @Test
    void actuatorHealthIsPublicAndUp() {
        ResponseEntity<String> response = client().get().uri("/actuator/health").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        // Anonymous callers get the status only; component details are for administrators.
        assertThat(response.getBody()).doesNotContain("\"db\"");
    }

    @Test
    void actuatorPrometheusIsPublic_butMetricsRequireAnAdministrator() {
        ResponseEntity<String> response = client().get().uri("/actuator/prometheus").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");

        ResponseEntity<String> metrics = client().get().uri("/actuator/metrics").retrieve().toEntity(String.class);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void openApiDocumentIsPublic() {
        ResponseEntity<String> response = client().get().uri("/v3/api-docs").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"openapi\"").contains("bearerAuth");
    }

    @Test
    void protectedEndpointWithoutTokenReturnsContractError() {
        ResponseEntity<String> response = client().get().uri("/api/v1/users")
                .header("X-Request-Id", "smoke-401")
                .retrieve().toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("smoke-401");
        assertThat(response.getBody())
                .contains("\"requestId\":\"smoke-401\"")
                .contains("\"status\":401")
                .contains("\"code\":\"AUTH_UNAUTHENTICATED\"")
                .contains("\"fieldErrors\":[]");
    }
}
