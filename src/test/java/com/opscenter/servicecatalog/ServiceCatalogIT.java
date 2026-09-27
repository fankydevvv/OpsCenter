package com.opscenter.servicecatalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.identity.testsupport.IdentityIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Service Catalog end to end (07-TC §8, 04-API §5, blueprint §6/§7.1) on PostgreSQL 17 migrated with
 * V007 / V007.1: create with environments and owners, alias normalisation, duplicate environments,
 * owner mapping through {@code PUT ownership}, optimistic locking, soft delete, filters, RBAC and
 * the audit trail. Tests log in through the API like a client; every code is unique per test.
 */
class ServiceCatalogIT extends IdentityIntegrationTest {

    private static String serviceCode() {
        return unique("svc-");
    }

    private JsonNode createService(String token, Map<String, Object> body) throws Exception {
        MvcResult result = mvc.perform(jsonRequest(post("/api/v1/services"), token, body))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    private Integer auditCount(String action, String resourceId) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ? and resource_id = ?::uuid",
                Integer.class, action, resourceId);
    }

    @Test
    void TC_SVC_001_adminCreatesAService_withEnvironmentsAndOwners_idempotently() throws Exception {
        String admin = adminToken();
        String code = serviceCode();
        Map<String, Object> request = Map.of(
                "code", code.toUpperCase(),
                "name", "Payment API",
                "description", "Handles card payments",
                "owningTeamId", TEAM_PAYMENT_ID.toString(),
                "primaryOwnerId", ENGINEER_A_ID.toString(),
                "backupOwnerId", ENGINEER_B_ID.toString(),
                "metadata", Map.of("tier", "gold", "language", "java"),
                "environments", List.of(Map.of("environmentCode", "prod", "healthEndpoint", "https://pay.example.com/health"),
                        Map.of("environmentCode", "dev")));

        MvcResult first = mvc.perform(jsonRequest(post("/api/v1/services"), admin, request).header("Idempotency-Key", code))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.organizationId").value(DEFAULT_ORG_ID.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.owningTeam.code").value("PAYMENT"))
                .andExpect(jsonPath("$.primaryOwner.username").value("engineer.a"))
                .andExpect(jsonPath("$.backupOwner.displayName").value("Engineer B"))
                .andExpect(jsonPath("$.metadata.tier").value("gold"))
                .andExpect(jsonPath("$.environments[*].environmentCode", contains("DEV", "PRODUCTION")))
                .andExpect(jsonPath("$.environments[1].healthEndpoint").value("https://pay.example.com/health"))
                .andExpect(jsonPath("$.owners").isEmpty())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        String id = body(first).get("id").asString();

        // TC-IDEMP adapted: the same key replays the same service, another body with it is refused
        mvc.perform(jsonRequest(post("/api/v1/services"), admin, request).header("Idempotency-Key", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(jsonRequest(post("/api/v1/services"), admin, Map.of("code", code + "x", "name", "Other"))
                        .header("Idempotency-Key", code))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(jsonRequest(post("/api/v1/services"), admin, Map.of("code", code, "name", "Again")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_CODE_TAKEN"));

        assertThat(jdbc.queryForObject("select count(*) from services where code = ?", Integer.class, code)).isEqualTo(1);
        assertThat(jdbc.queryForList("select environment_code from service_environments where service_id = ?::uuid "
                + "order by environment_code", String.class, id)).containsExactly("DEV", "PRODUCTION");
        Map<String, Object> audit = jdbc.queryForMap("select actor_id, organization_id, before_data from audit_logs "
                + "where action = 'SERVICE_CREATED' and resource_id = ?::uuid", id);
        assertThat(audit.get("actor_id")).isEqualTo(ADMIN_ID);
        assertThat(audit.get("organization_id")).isEqualTo(DEFAULT_ORG_ID);
        assertThat(audit.get("before_data")).isNull();
    }

    @Test
    void create_rejectsInvalidOwners_andCredentialMetadata() throws Exception {
        String admin = adminToken();

        mvc.perform(jsonRequest(post("/api/v1/services"), admin, Map.of("code", serviceCode(), "name", "X",
                        "primaryOwnerId", ENGINEER_A_ID.toString(), "backupOwnerId", ENGINEER_A_ID.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SERVICE_OWNERS_MUST_DIFFER"));
        mvc.perform(jsonRequest(post("/api/v1/services"), admin, Map.of("code", serviceCode(), "name", "X",
                        "owningTeamId", UUID.randomUUID().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
        mvc.perform(jsonRequest(post("/api/v1/services"), admin, Map.of("code", serviceCode(), "name", "X",
                        "primaryOwnerId", UUID.randomUUID().toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mvc.perform(jsonRequest(post("/api/v1/services"), admin, Map.of("code", serviceCode(), "name", "X",
                        "metadata", Map.of("apiKey", "sk-live-123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(jsonRequest(post("/api/v1/services"), admin, Map.of("code", serviceCode(), "name", "X",
                        "environments", List.of(Map.of("environmentCode", "prod"), Map.of("environmentCode", "PRODUCTION")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_ENVIRONMENT_EXISTS"));
    }

    @Test
    void TC_SVC_002_addingAProdEnvironment_createsItsOwnRow() throws Exception {
        String admin = adminToken();
        String id = createService(admin, Map.of("code", serviceCode(), "name", "Billing")).get("id").asString();

        MvcResult created = mvc.perform(jsonRequest(post("/api/v1/services/" + id + "/environments"), admin,
                        Map.of("environmentCode", "Prod", "healthEndpoint", "https://billing.example.com/health",
                                "dashboardUrl", "http://localhost:3100/d/billing", "status", "DEGRADED")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.environmentCode").value("PRODUCTION"))
                .andExpect(jsonPath("$.serviceId").value(id))
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        String environmentId = body(created).get("id").asString();

        mvc.perform(get("/api/v1/services/" + id + "/environments").header("Authorization", bearer(engineerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].environmentCode", contains("PRODUCTION")));
        assertThat(jdbc.queryForObject("select count(*) from service_environments where service_id = ?::uuid",
                Integer.class, id)).isEqualTo(1);
        assertThat(auditCount("SERVICE_ENVIRONMENT_CREATED", environmentId)).isEqualTo(1);
    }

    @Test
    void TC_SVC_003_duplicateEnvironmentCode_isRejectedByTheServiceAndByTheDatabase() throws Exception {
        String admin = adminToken();
        String id = createService(admin, Map.of("code", serviceCode(), "name", "Search",
                "environments", List.of(Map.of("environmentCode", "PRODUCTION")))).get("id").asString();

        mvc.perform(jsonRequest(post("/api/v1/services/" + id + "/environments"), admin, Map.of("environmentCode", "prd")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_ENVIRONMENT_EXISTS"));
        // the unique constraint uk_service_env is the last line of defence
        assertThatThrownBy(() -> jdbc.update("insert into service_environments (id, service_id, environment_code) "
                + "values (?::uuid, ?::uuid, 'PRODUCTION')", UUID.randomUUID().toString(), id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void TC_SVC_005_ownershipMapping_isReplacedAsAWhole_andReturnedByTheQueries() throws Exception {
        String admin = adminToken();
        String code = serviceCode();
        String id = createService(admin, Map.of("code", code, "name", "Checkout",
                "owningTeamId", TEAM_PAYMENT_ID.toString(), "primaryOwnerId", ENGINEER_A_ID.toString())).get("id").asString();

        Map<String, Object> ownership = Map.of(
                "owningTeamId", TEAM_PLATFORM_ID.toString(),
                "primaryOwnerId", ENGINEER_B_ID.toString(),
                "additionalOwners", List.of(
                        Map.of("teamId", TEAM_PAYMENT_ID.toString(), "ownershipType", "SUPPORTING_TEAM", "priority", 2),
                        Map.of("userId", COORDINATOR_ID.toString(), "ownershipType", "ON_CALL_CONTACT")),
                "version", 0);
        mvc.perform(jsonRequest(put("/api/v1/services/" + id + "/ownership"), admin, ownership))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owningTeam.code").value("PLATFORM"))
                .andExpect(jsonPath("$.primaryOwner.username").value("engineer.b"))
                .andExpect(jsonPath("$.backupOwner").doesNotExist())
                .andExpect(jsonPath("$.owners[0].ownershipType").value("SUPPORTING_TEAM"))
                .andExpect(jsonPath("$.owners[0].team.code").value("PAYMENT"))
                .andExpect(jsonPath("$.owners[0].priority").value(2))
                .andExpect(jsonPath("$.owners[1].ownershipType").value("ON_CALL_CONTACT"))
                .andExpect(jsonPath("$.owners[1].user.username").value("coordinator"))
                .andExpect(jsonPath("$.version").value(1));

        // the queries return the mapping: detail, list filter by team, rows in service_owners
        mvc.perform(get("/api/v1/services/" + id).header("Authorization", bearer(engineerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owners.length()").value(2));
        mvc.perform(get("/api/v1/services?owningTeamId=" + TEAM_PLATFORM_ID + "&q=" + code)
                        .header("Authorization", bearer(engineerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value(code))
                .andExpect(jsonPath("$.items[0].primaryOwner.username").value("engineer.b"));
        assertThat(jdbc.queryForObject("select count(*) from service_owners where service_id = ?::uuid", Integer.class, id))
                .isEqualTo(2);

        // PUT replaces: re-sending one of the owners plus nothing else leaves exactly one row
        mvc.perform(jsonRequest(put("/api/v1/services/" + id + "/ownership"), admin, Map.of(
                        "additionalOwners", List.of(Map.of("userId", COORDINATOR_ID.toString(), "ownershipType", "ON_CALL_CONTACT")),
                        "version", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owningTeam").doesNotExist())
                .andExpect(jsonPath("$.primaryOwner").doesNotExist())
                .andExpect(jsonPath("$.owners.length()").value(1))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(jsonRequest(put("/api/v1/services/" + id + "/ownership"), admin, Map.of("version", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENCY_VERSION_CONFLICT"));
        mvc.perform(jsonRequest(put("/api/v1/services/" + id + "/ownership"), admin, Map.of(
                        "additionalOwners", List.of(Map.of("teamId", TEAM_PAYMENT_ID.toString(),
                                "userId", COORDINATOR_ID.toString(), "ownershipType", "TECHNICAL_OWNER")),
                        "version", 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SERVICE_OWNER_TARGET_INVALID"));
        // a change of the additional owners alone also bumps the version (stale clients get 409)
        mvc.perform(jsonRequest(put("/api/v1/services/" + id + "/ownership"), admin, Map.of(
                        "additionalOwners", List.of(Map.of("userId", COORDINATOR_ID.toString(), "ownershipType", "ON_CALL_CONTACT"),
                                Map.of("teamId", TEAM_PAYMENT_ID.toString(), "ownershipType", "SUPPORTING_TEAM")),
                        "version", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owners.length()").value(2))
                .andExpect(jsonPath("$.version").value(3));
        String coordinatorRowId = jdbc.queryForObject("select id::text from service_owners where service_id = ?::uuid "
                + "and user_id = ?", String.class, id, COORDINATOR_ID);
        // a priority-only change still moves the version exactly once; an identical PUT changes nothing
        Map<String, Object> priorityOnly = Map.of(
                "additionalOwners", List.of(Map.of("userId", COORDINATOR_ID.toString(), "ownershipType", "ON_CALL_CONTACT"),
                        Map.of("teamId", TEAM_PAYMENT_ID.toString(), "ownershipType", "SUPPORTING_TEAM", "priority", 5)),
                "version", 3);
        mvc.perform(jsonRequest(put("/api/v1/services/" + id + "/ownership"), admin, priorityOnly))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owners[0].priority").value(5))
                .andExpect(jsonPath("$.version").value(4));
        mvc.perform(jsonRequest(put("/api/v1/services/" + id + "/ownership"), admin, Map.of(
                        "additionalOwners", priorityOnly.get("additionalOwners"), "version", 4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
        assertThat(jdbc.queryForObject("select id::text from service_owners where service_id = ?::uuid and user_id = ?",
                String.class, id, COORDINATOR_ID)).as("unchanged owner keeps its row").isEqualTo(coordinatorRowId);
        assertThat(jdbc.queryForObject("select version from services where id = ?::uuid", Long.class, id)).isEqualTo(4L);

        assertThat(auditCount("SERVICE_OWNERS_CHANGED", id)).isEqualTo(5);
        Map<String, Object> firstChange = jdbc.queryForMap("select before_data, after_data from audit_logs "
                + "where action = 'SERVICE_OWNERS_CHANGED' and resource_id = ?::uuid "
                + "and after_data -> 'owningTeam' ->> 'code' = 'PLATFORM'", id);
        JsonNode before = json.readTree(firstChange.get("before_data").toString());
        JsonNode after = json.readTree(firstChange.get("after_data").toString());
        assertThat(before.get("owningTeam").get("code").asString()).isEqualTo("PAYMENT");
        assertThat(after.get("owningTeam").get("code").asString()).isEqualTo("PLATFORM");
    }

    @Test
    void ownersMustBeActive() throws Exception {
        String admin = adminToken();
        String username = unique("locked.");
        String lockedId = createUser(admin, username, List.of("ENGINEER")).get("id").asString();
        mvc.perform(jsonRequest(post("/api/v1/users/" + lockedId + "/lock"), admin, Map.of("reason", "left the company")))
                .andExpect(status().is2xxSuccessful());
        String id = createService(admin, Map.of("code", serviceCode(), "name", "Ledger")).get("id").asString();

        mvc.perform(jsonRequest(put("/api/v1/services/" + id + "/ownership"), admin,
                        Map.of("primaryOwnerId", lockedId, "version", 0)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SERVICE_OWNER_INACTIVE"));
    }

    @Test
    void TC_RBAC_003_engineerAndCoordinator_canReadButNotChangeTheCatalog() throws Exception {
        String admin = adminToken();
        String id = createService(admin, Map.of("code", serviceCode(), "name", "Read only")).get("id").asString();

        for (String token : List.of(engineerToken(), coordinatorToken())) {
            mvc.perform(get("/api/v1/services/" + id).header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
            mvc.perform(jsonRequest(post("/api/v1/services"), token, Map.of("code", serviceCode(), "name", "Nope")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));
            mvc.perform(jsonRequest(patch("/api/v1/services/" + id), token, Map.of("name", "Nope", "version", 0)))
                    .andExpect(status().isForbidden());
            mvc.perform(jsonRequest(post("/api/v1/services/" + id + "/environments"), token, Map.of("environmentCode", "DEV")))
                    .andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("select name from services where id = ?::uuid", String.class, id)).isEqualTo("Read only");
    }

    @Test
    void patch_updatesDescriptiveFields_andActiveFalseIsTheSoftDelete() throws Exception {
        String admin = adminToken();
        String code = serviceCode();
        String id = createService(admin, Map.of("code", code, "name", "Reports", "description", "old")).get("id").asString();

        mvc.perform(jsonRequest(patch("/api/v1/services/" + id), admin,
                        Map.of("name", "Reporting", "description", "", "status", "DEGRADED", "version", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Reporting"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(jsonRequest(patch("/api/v1/services/" + id), admin, Map.of("name", "Stale", "version", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENCY_VERSION_CONFLICT"));

        mvc.perform(jsonRequest(patch("/api/v1/services/" + id), admin, Map.of("active", false, "version", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.deletedAt").isNotEmpty());
        Map<String, Object> row = jdbc.queryForMap("select is_active, deleted_at from services where id = ?::uuid", id);
        assertThat(row.get("is_active")).isEqualTo(false);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(auditCount("SERVICE_UPDATED", id)).isEqualTo(1);
        assertThat(auditCount("SERVICE_DEACTIVATED", id)).isEqualTo(1);

        String engineer = engineerToken();
        mvc.perform(get("/api/v1/services?q=" + code).header("Authorization", bearer(engineer)))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/services?active=false&q=" + code).header("Authorization", bearer(engineer)))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].active").value(false));
    }

    @Test
    void environmentPatch_changesEndpoints_andSoftDeletes() throws Exception {
        String admin = adminToken();
        JsonNode service = createService(admin, Map.of("code", serviceCode(), "name", "Gateway",
                "environments", List.of(Map.of("environmentCode", "STAGING", "metricEndpoint", "http://gw:9100/metrics"))));
        String environmentId = service.get("environments").get(0).get("id").asString();

        mvc.perform(jsonRequest(patch("/api/v1/service-environments/" + environmentId), admin,
                        Map.of("metricEndpoint", "", "dashboardUrl", "https://grafana.example.com/d/gw", "version", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricEndpoint").doesNotExist())
                .andExpect(jsonPath("$.dashboardUrl").value("https://grafana.example.com/d/gw"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(jsonRequest(patch("/api/v1/service-environments/" + environmentId), admin,
                        Map.of("active", false, "version", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/services/" + service.get("id").asString() + "/environments?active=true")
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(jsonRequest(patch("/api/v1/service-environments/" + UUID.randomUUID()), admin, Map.of("version", 0)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_ENVIRONMENT_NOT_FOUND"));
        assertThat(auditCount("SERVICE_ENVIRONMENT_UPDATED", environmentId)).isEqualTo(2);
    }

    @Test
    void list_filtersByStatusAndEnvironmentAlias_andWhitelistsSort() throws Exception {
        String admin = adminToken();
        String prefix = unique("flt-");
        createService(admin, Map.of("code", prefix + "-a", "name", "Alpha", "status", "MAINTENANCE",
                "environments", List.of(Map.of("environmentCode", "PRODUCTION"))));
        createService(admin, Map.of("code", prefix + "-b", "name", "Beta",
                "environments", List.of(Map.of("environmentCode", "DEV"))));
        String engineer = engineerToken();

        mvc.perform(get("/api/v1/services?q=" + prefix + "&sort=name,desc").header("Authorization", bearer(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].name", contains("Beta", "Alpha")))
                .andExpect(jsonPath("$.items[1].environments[0].environmentCode").value("PRODUCTION"));
        mvc.perform(get("/api/v1/services?q=" + prefix + "&environment=prod").header("Authorization", bearer(engineer)))
                .andExpect(jsonPath("$.items[*].code", contains(prefix + "-a")));
        mvc.perform(get("/api/v1/services?q=" + prefix + "&status=MAINTENANCE").header("Authorization", bearer(engineer)))
                .andExpect(jsonPath("$.items[*].code", contains(prefix + "-a")));
        mvc.perform(get("/api/v1/services?sort=metadata").header("Authorization", bearer(engineer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/services?size=100").header("Authorization", bearer(engineer)))
                .andExpect(jsonPath("$.items[*].code", hasItem("odoo-erp")));
        mvc.perform(get("/api/v1/services/" + UUID.randomUUID()).header("Authorization", bearer(engineer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
    }
}
