package com.opscenter.shared.infrastructure.security;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.shared.application.CurrentUser;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Fills {@code created_at/created_by/updated_at/updated_by} automatically (03-DB §3.4, D-11).
 * <p>
 * The auditor is the {@code sub} claim of the caller's JWT, read through the {@link CurrentUser}
 * port; system jobs and seeds leave it {@code null}. Timestamps come from the shared UTC
 * {@link Clock} rather than the JVM default zone, so tests can freeze time and the database only
 * ever sees UTC (03-DB §3.2).
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<UUID> auditorAware(CurrentUser currentUser) {
        return currentUser::actorId;
    }

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
