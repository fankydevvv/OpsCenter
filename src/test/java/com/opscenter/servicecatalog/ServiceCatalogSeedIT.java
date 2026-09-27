package com.opscenter.servicecatalog;

import java.util.List;
import java.util.Map;

import com.opscenter.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migrations of this increment on a clean database: V007 (schema + constraints), V007.1 (the
 * permission matrix of blueprint §6 for service.* and system.read) and the demo data of
 * {@code db/seed-demo} (D-64) that the Prometheus demo target maps to.
 */
class ServiceCatalogSeedIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void migrationsApplied_inVersionOrder() {
        List<String> scripts = jdbc.queryForList(
                "select script from flyway_schema_history where success order by installed_rank", String.class);

        assertThat(scripts).containsSubsequence("V006__review_fixes.sql", "V007__create_service_catalog.sql",
                "V007_1__seed_catalog_and_system_permissions.sql");
        assertThat(scripts).contains("V011_1__seed_demo_services.sql");
    }

    @Test
    void permissionMatrix_matchesBlueprintSection6() {
        Map<String, List<String>> expected = Map.of(
                "service.read", List.of("ADMIN", "COORDINATOR", "ENGINEER"),
                "service.create", List.of("ADMIN"),
                "service.update", List.of("ADMIN"),
                "system.read", List.of("ADMIN"));
        expected.forEach((permission, roles) -> assertThat(jdbc.queryForList(
                "select r.code from role_permissions rp join roles r on r.id = rp.role_id "
                        + "join permissions p on p.id = rp.permission_id where p.code = ? order by r.code",
                String.class, permission)).as(permission).containsExactlyElementsOf(roles));
        assertThat(jdbc.queryForObject("select id::text from permissions where code = 'system.read'", String.class))
                .isEqualTo("00000000-0000-4000-8000-000000001028");
    }

    @Test
    void demoServices_areSeeded_forThePrometheusDemoTarget() {
        Map<String, Object> odoo = jdbc.queryForMap("select s.code, s.is_active, t.code as team, e.environment_code, "
                + "e.metric_endpoint from services s join teams t on t.id = s.owning_team_id "
                + "join service_environments e on e.service_id = s.id where s.code = 'odoo-erp'");
        assertThat(odoo).containsEntry("is_active", true).containsEntry("team", "PLATFORM")
                .containsEntry("environment_code", "DEV").containsEntry("metric_endpoint", "http://demo-target:9100/metrics");
        assertThat(jdbc.queryForObject("select count(*) from services where code = 'opscenter-backend'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void databaseConstraints_backTheDomainRules() {
        // D-33 code format and D-34 exactly-one owner target are CHECK constraints, not only Java rules
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname in "
                        + "('ck_services_code_format', 'ck_services_owners_differ', 'ck_service_owners_one_target', "
                        + "'uk_service_env', 'uk_services_org_code')", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname in "
                + "('uk_service_owners_team', 'uk_service_owners_user')", Integer.class)).isEqualTo(2);
    }
}
