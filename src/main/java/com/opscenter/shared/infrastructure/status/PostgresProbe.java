package com.opscenter.shared.infrastructure.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.opscenter.shared.application.status.ComponentProbe;
import com.opscenter.shared.application.status.ProbeResult;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL: server version ({@code current_setting('server_version')}), database name and the
 * last applied Flyway migration - the two facts an administrator asks first after a deployment
 * (05-DEPLOY §12 "migration applied"). Uses its own {@link JdbcTemplate} with a query timeout so a
 * locked table cannot hang the status page.
 */
@Component
public class PostgresProbe implements ComponentProbe {

    private final JdbcTemplate jdbc;

    public PostgresProbe(DataSource dataSource, SystemProbeProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout((int) Math.max(1, properties.timeout().toSeconds()));
    }

    @Override
    public String name() {
        return "postgres";
    }

    @Override
    public ProbeResult probe() {
        String version = jdbc.queryForObject("select current_setting('server_version')", String.class);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("database", jdbc.queryForObject("select current_database()", String.class));
        List<String> flyway = jdbc.queryForList(
                "select version from flyway_schema_history where success and version is not null "
                        + "order by installed_rank desc limit 1", String.class);
        details.put("flywayVersion", flyway.isEmpty() ? null : flyway.getFirst());
        return ProbeResult.up(version, details);
    }
}
