package com.opscenter.shared.infrastructure.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** D-28: exponential backoff doubles per failed attempt, is capped, and drives {@code next_attempt_at}. */
class OutboxBackoffTest {

    private static final Duration TWO_SECONDS = Duration.ofSeconds(2);
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    @Test
    void doublesPerAttempt_upToTheCap() {
        assertThat(OutboxEventEntity.backoffFor(1, TWO_SECONDS, FIVE_MINUTES)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxEventEntity.backoffFor(2, TWO_SECONDS, FIVE_MINUTES)).isEqualTo(Duration.ofSeconds(4));
        assertThat(OutboxEventEntity.backoffFor(3, TWO_SECONDS, FIVE_MINUTES)).isEqualTo(Duration.ofSeconds(8));
        assertThat(OutboxEventEntity.backoffFor(8, TWO_SECONDS, FIVE_MINUTES)).isEqualTo(Duration.ofSeconds(256));
        assertThat(OutboxEventEntity.backoffFor(9, TWO_SECONDS, FIVE_MINUTES)).isEqualTo(FIVE_MINUTES);
        assertThat(OutboxEventEntity.backoffFor(100, TWO_SECONDS, FIVE_MINUTES)).isEqualTo(FIVE_MINUTES);
        assertThat(OutboxEventEntity.backoffFor(5, Duration.ZERO, Duration.ZERO)).isEqualTo(Duration.ZERO);
    }

    @Test
    void failedAttemptSchedulesTheNextOne_andParksAfterMaxRetries() {
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        OutboxEventEntity event = new OutboxEventEntity(UUID.randomUUID(), "User", UUID.randomUUID(), "UserLocked",
                "{}", now);
        assertThat(event.getNextAttemptAt()).isEqualTo(now);

        event.markAttemptFailed("boom", 2, now, TWO_SECONDS, FIVE_MINUTES);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(2));

        event.markAttemptFailed("boom", 2, now.plusSeconds(2), TWO_SECONDS, FIVE_MINUTES);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(2);
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(6));
    }
}
