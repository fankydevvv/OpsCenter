package com.opscenter.shared.infrastructure.json;

import java.time.Clock;
import java.util.TimeZone;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * JSON and time conventions shared by REST responses, audit snapshots and outbox payloads.
 * <p>
 * Spring Boot 4 ships Jackson 3 ({@code tools.jackson}); its defaults already render
 * {@code java.time} values as ISO-8601 strings (the date features moved from
 * {@code SerializationFeature} to {@code DateTimeFeature} in Jackson 3). The customizer pins the
 * behaviour the API contract relies on (04-API §2.3 sample {@code "2026-09-26T04:00:00Z"}): UTC,
 * no numeric timestamps, unknown properties tolerated so clients can evolve independently. The
 * single {@link Clock} bean makes every "now" in the application injectable and testable.
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfig {

    @Bean
    public JsonMapperBuilderCustomizer opscenterJsonMapperCustomizer() {
        return builder -> builder
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .defaultTimeZone(TimeZone.getTimeZone("UTC"));
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
