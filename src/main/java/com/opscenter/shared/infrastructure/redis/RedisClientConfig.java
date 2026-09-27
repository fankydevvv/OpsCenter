package com.opscenter.shared.infrastructure.redis;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the Lettuce Redis client fail fast when Redis is down (blueprint D-51..D-53, review finding
 * "Redis outage costs ~8 s per webhook").
 * <p>
 * Lettuce's default {@code disconnectedBehavior} <em>queues</em> commands while it reconnects, so
 * with Redis stopped every call waits the whole command timeout (2 s) before the caller can fall
 * back. {@code REJECT_COMMANDS} fails them immediately while the connection is known to be down;
 * {@code autoReconnect} stays on, so the client heals by itself when Redis returns.
 * <p>
 * Spring Boot builds the {@link ClientOptions} from {@code spring.data.redis.*} (connect timeout ...)
 * and then lets this customizer adjust the builder - we change only these two settings.
 */
@Configuration(proxyBeanMethods = false)
public class RedisClientConfig {

    @Bean
    LettuceClientOptionsBuilderCustomizer redisFailFastClientOptions() {
        return builder -> builder
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
