package com.opscenter.incident;

import java.util.List;
import java.util.Map;

import com.opscenter.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V008-V011 on a clean database: migration order, the permission matrix of blueprint §6 for the
 * alert/incident codes, the seeded Alertmanager source and the database guards of D-47/D-54.
 */
class Sprint2PermissionSeedIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationsApplied_inVersionOrder_withTheDemoSeedLast() {
        List<String> scripts = jdbc.queryForList(
                "select script from flyway_schema_history where success order by installed_rank", String.class);
        assertThat(scripts).containsSubsequence("V007_1__seed_catalog_and_system_permissions.sql",
                "V008__create_integration_source.sql", "V009__create_alert.sql", "V010__create_incident.sql",
                "V011__seed_sprint2_permissions.sql", "V011_1__seed_demo_services.sql");
    }

    @Test
    void permissionMatrix_matchesBlueprintSection6() {
        Map<String, List<String>> expected = Map.of(
                "alert.read", List.of("ADMIN", "COORDINATOR", "ENGINEER"),
                "alert.raw.read", List.of("ADMIN"),
                "incident.read", List.of("ADMIN", "COORDINATOR", "ENGINEER"),
                "incident.acknowledge", List.of("ADMIN", "COORDINATOR", "ENGINEER"),
                "incident.investigate", List.of("ADMIN", "ENGINEER"),
                "incident.mitigate", List.of("ADMIN", "ENGINEER"),
                "incident.resolve", List.of("ADMIN", "ENGINEER"),
                "incident.close", List.of("ADMIN", "COORDINATOR", "ENGINEER"));
        expected.forEach((permission, roles) -> assertThat(jdbc.queryForList(
                "select r.code from role_permissions rp join roles r on r.id = rp.role_id "
                        + "join permissions p on p.id = rp.permission_id where p.code = ? order by r.code",
                String.class, permission)).as(permission).containsExactlyElementsOf(roles));
        // service.* / system.read come from V007.1 and were not inserted twice
        assertThat(jdbc.queryForObject("select count(*) from role_permissions rp join permissions p "
                + "on p.id = rp.permission_id where p.code = 'system.read'", Integer.class)).isEqualTo(1);
    }

    @Test
    void alertmanagerSource_isSeeded_withASecretReferenceOnly() {
        Map<String, Object> source = jdbc.queryForMap("select code, source_type, auth_type, secret_ref, enabled "
                + "from integration_sources where id = '00000000-0000-4000-8000-000000000301'");
        assertThat(source).containsEntry("code", "alertmanager").containsEntry("source_type", "ALERTMANAGER")
                .containsEntry("auth_type", "TOKEN").containsEntry("secret_ref", "env:OPSCENTER_ALERTMANAGER_TOKEN")
                .containsEntry("enabled", true);
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname = "
                + "'fk_idempotency_integration_source'", Integer.class)).isEqualTo(1);
    }

    @Test
    void databaseGuards_ofDedupAndGrouping_exist() {
        assertThat(jdbc.queryForList("select indexname from pg_indexes where indexname in "
                + "('uk_alerts_firing_fingerprint', 'uk_incidents_open_correlation', 'uk_incident_alerts_primary', "
                + "'idx_alerts_unmapped') order by indexname", String.class))
                .containsExactly("idx_alerts_unmapped", "uk_alerts_firing_fingerprint", "uk_incident_alerts_primary",
                        "uk_incidents_open_correlation");
        assertThat(jdbc.queryForObject("select count(*) from pg_class where relname = 'incident_no_seq' and relkind = 'S'",
                Integer.class)).isEqualTo(1);
    }
}
