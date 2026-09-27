package com.opscenter.shared.infrastructure.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import com.opscenter.shared.application.status.ComponentProbe;
import com.opscenter.shared.application.status.ProbeResult;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Redis: {@code PING} plus {@code INFO server} for {@code redis_version}, mode and uptime.
 * Redis only coordinates (lock, cache, rate limit - 05-DEPLOY §23.1), so DOWN here means
 * "degraded", not "platform down".
 */
@Component
public class RedisProbe implements ComponentProbe {

    private final RedisConnectionFactory connectionFactory;

    public RedisProbe(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public ProbeResult probe() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String pong = connection.ping();
            Properties info = connection.serverCommands().info("server");
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("ping", pong);
            if (info != null) {
                details.put("mode", info.getProperty("redis_mode"));
                String uptime = info.getProperty("uptime_in_seconds");
                details.put("uptimeSeconds", uptime == null ? null : Long.valueOf(uptime.trim()));
            }
            return ProbeResult.up(info == null ? null : info.getProperty("redis_version"), details);
        }
    }
}
