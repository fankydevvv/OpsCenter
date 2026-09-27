package com.opscenter.shared.infrastructure.messaging;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Tuning of the outbox relay ({@code opscenter.outbox.relay.*}, D-14, D-28).
 * <p>
 * With the defaults an event is retried at 2 s, 4 s, 8 s ... capped at 5 min, so 100 attempts
 * cover roughly eight hours of broker outage before the row is parked as {@code FAILED}.
 *
 * @param enabled        whether the scheduled relay runs (off in tests, which call it explicitly)
 * @param delay          pause between two polling rounds
 * @param batchSize      maximum events published per round
 * @param maxRetries     attempts before an event is parked as {@code FAILED}
 * @param initialBackoff wait after the first failed attempt; doubles on every further failure
 * @param maxBackoff     upper bound of the wait between two attempts
 */
@Validated
@ConfigurationProperties(prefix = "opscenter.outbox.relay")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @NotNull @DefaultValue("PT2S") Duration delay,
        @Min(1) @DefaultValue("50") int batchSize,
        @Min(1) @DefaultValue("100") int maxRetries,
        @NotNull @DefaultValue("PT2S") Duration initialBackoff,
        @NotNull @DefaultValue("PT5M") Duration maxBackoff) {
}
