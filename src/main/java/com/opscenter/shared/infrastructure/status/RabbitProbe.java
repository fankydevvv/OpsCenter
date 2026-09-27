package com.opscenter.shared.infrastructure.status;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import com.opscenter.shared.application.status.ComponentProbe;
import com.opscenter.shared.application.status.ProbeErrors;
import com.opscenter.shared.application.status.ProbeResult;
import com.opscenter.shared.infrastructure.persistence.OutboxEventStatus;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ: broker version from the AMQP connection's server properties, plus the outbox backlog
 * ({@code outbox_events} PENDING / FAILED - D-14, D-28). The backlog is read from PostgreSQL first,
 * so it is shown even when the broker is down - which is exactly when it grows.
 */
@Component
public class RabbitProbe implements ComponentProbe {

    private final RabbitTemplate rabbitTemplate;
    private final JdbcTemplate jdbc;

    public RabbitProbe(RabbitTemplate rabbitTemplate, DataSource dataSource, SystemProbeProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout((int) Math.max(1, properties.timeout().toSeconds()));
    }

    @Override
    public String name() {
        return "rabbitmq";
    }

    @Override
    public ProbeResult probe() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("outboxPending", countOutbox(OutboxEventStatus.PENDING));
        details.put("outboxFailed", countOutbox(OutboxEventStatus.FAILED));
        try {
            Object version = rabbitTemplate.execute(channel -> channel.getConnection().getServerProperties().get("version"));
            return ProbeResult.up(version == null ? null : version.toString(), details);
        }
        catch (RuntimeException ex) {
            return ProbeResult.down(ProbeErrors.describe(ex), details);
        }
    }

    private Long countOutbox(OutboxEventStatus status) {
        try {
            return jdbc.queryForObject("select count(*) from outbox_events where status = ?", Long.class, status.name());
        }
        catch (RuntimeException ex) {
            return null;
        }
    }
}
