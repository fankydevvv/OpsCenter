package com.opscenter.shared.infrastructure.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} for background jobs (outbox relay today, retention purges later) and
 * binds {@link OutboxProperties}. Kept as its own tiny class so the "why is there a scheduler
 * thread" question has one obvious answer.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class SchedulingConfig {
}
