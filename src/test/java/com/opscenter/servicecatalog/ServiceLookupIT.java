package com.opscenter.servicecatalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.opscenter.identity.testsupport.IdentityIntegrationTest;
import com.opscenter.servicecatalog.application.MappingStatus;
import com.opscenter.servicecatalog.application.ResolvedService;
import com.opscenter.servicecatalog.application.ServiceLookup;
import com.opscenter.servicecatalog.application.ServiceRef;
import com.opscenter.servicecatalog.infrastructure.RedisServiceResolutionCache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The catalog's public API as the alert module will use it (FR-ALT-05, blueprint D-44, D-48, D-52),
 * against real PostgreSQL and Redis: label resolution of the seeded demo service, the Redis hash
 * layout, negative caching, and eviction after every catalog change so no stale mapping survives a
 * commit.
 */
class ServiceLookupIT extends IdentityIntegrationTest {

    private static final UUID ODOO_ID = UUID.fromString("00000000-0000-4000-8000-000000000401");
    private static final UUID ODOO_DEV_ID = UUID.fromString("00000000-0000-4000-8000-000000000411");

    @Autowired ServiceLookup lookup;
    @Autowired StringRedisTemplate redis;

    private static String cacheKey(String code) {
        return RedisServiceResolutionCache.key(DEFAULT_ORG_ID, code);
    }

    @Test
    void TC_ALT_005_prometheusLabelsOfTheDemoTarget_resolveToTheSeededService() {
        redis.delete(cacheKey("odoo-erp"));
        Map<String, String> labels = Map.of("alertname", "TargetDown", "service", "odoo-erp", "environment", "DEV",
                "instance", "demo-target:9100", "job", "odoo-erp-demo");

        ResolvedService resolved = lookup.resolveByLabels(DEFAULT_ORG_ID, labels);

        assertThat(resolved.mappingStatus()).isEqualTo(MappingStatus.MAPPED);
        assertThat(resolved.serviceId()).isEqualTo(ODOO_ID);
        assertThat(resolved.serviceEnvironmentId()).isEqualTo(ODOO_DEV_ID);
        assertThat(resolved.owningTeamId()).as("TC-SVC-005: team of the service").isEqualTo(TEAM_PLATFORM_ID);
        assertThat(resolved.serviceName()).isEqualTo("Odoo ERP (reference monitored system)");
        // cached as one hash per service code, one field per environment, with a TTL (D-52)
        Object cached = redis.opsForHash().get(cacheKey("odoo-erp"), "DEV");
        assertThat(cached).isEqualTo(ODOO_ID + "|" + ODOO_DEV_ID + "|" + TEAM_PLATFORM_ID
                + "|Odoo ERP (reference monitored system)");
        assertThat(redis.getExpire(cacheKey("odoo-erp"), TimeUnit.SECONDS)).isBetween(1L, 600L);
        // a second call with an alias hits the cache and gives the same answer
        assertThat(lookup.resolveByLabels(DEFAULT_ORG_ID, Map.of("app", "ODOO-ERP", "env", "development")))
                .isEqualTo(resolved);
    }

    @Test
    void TC_ALT_006_unknownCode_isUnmapped_andNegativelyCached() {
        String code = unique("ghost-");

        ResolvedService resolved = lookup.resolve(DEFAULT_ORG_ID, code, "prod");

        assertThat(resolved.mappingStatus()).isEqualTo(MappingStatus.UNMAPPED);
        assertThat(resolved.serviceCode()).isEqualTo(code);
        assertThat(resolved.environment()).isEqualTo("PRODUCTION");
        assertThat(redis.opsForHash().get(cacheKey(code), "PRODUCTION")).isEqualTo("NONE");
        // negative answers live only briefly (review finding: a stale NONE must not hide a new service for 10 min)
        assertThat(redis.getExpire(cacheKey(code), TimeUnit.SECONDS)).isBetween(1L, 30L);
    }

    @Test
    void catalogChanges_evictTheCacheAfterCommit_soResolutionFollowsTheCatalog() throws Exception {
        String admin = adminToken();
        String code = unique("lookup-");
        // an alert arrives BEFORE the service is registered -> cached NONE
        assertThat(lookup.resolve(DEFAULT_ORG_ID, code, "DEV").mapped()).isFalse();

        MvcResult created = mvc.perform(jsonRequest(post("/api/v1/services"), admin, Map.of("code", code, "name", "Lookup",
                        "owningTeamId", TEAM_PAYMENT_ID.toString())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode service = body(created);
        String id = service.get("id").asString();
        assertThat(redis.hasKey(cacheKey(code))).as("create evicts the negative entry").isFalse();

        ResolvedService mappedWithoutEnvironment = lookup.resolve(DEFAULT_ORG_ID, code, "DEV");
        assertThat(mappedWithoutEnvironment.mapped()).isTrue();
        assertThat(mappedWithoutEnvironment.environmentRegistered()).isFalse();
        assertThat(mappedWithoutEnvironment.owningTeamId()).isEqualTo(TEAM_PAYMENT_ID);

        mvc.perform(jsonRequest(post("/api/v1/services/" + id + "/environments"), admin, Map.of("environmentCode", "dev")))
                .andExpect(status().isCreated());
        assertThat(lookup.resolve(DEFAULT_ORG_ID, code, "DEV").environmentRegistered()).isTrue();

        mvc.perform(jsonRequest(put("/api/v1/services/" + id + "/ownership"), admin,
                        Map.of("owningTeamId", TEAM_PLATFORM_ID.toString(), "version", 0)))
                .andExpect(status().isOk());
        assertThat(lookup.resolve(DEFAULT_ORG_ID, code, "DEV").owningTeamId()).isEqualTo(TEAM_PLATFORM_ID);

        // D-36: a deactivated service is never chosen - its alerts become UNMAPPED
        mvc.perform(jsonRequest(patch("/api/v1/services/" + id), admin, Map.of("active", false, "version", 1)))
                .andExpect(status().isOk());
        assertThat(lookup.resolve(DEFAULT_ORG_ID, code, "DEV").mappingStatus()).isEqualTo(MappingStatus.UNMAPPED);
    }

    @Test
    void findRefs_returnsServicesInOneBatch() {
        Map<UUID, ServiceRef> refs = lookup.findRefs(List.of(ODOO_ID, UUID.randomUUID(), ODOO_ID));

        assertThat(refs).containsOnlyKeys(ODOO_ID);
        assertThat(refs.get(ODOO_ID).code()).isEqualTo("odoo-erp");
        assertThat(refs.get(ODOO_ID).owningTeamId()).isEqualTo(TEAM_PLATFORM_ID);
        assertThat(lookup.findRefs(List.of())).isEmpty();
    }
}
