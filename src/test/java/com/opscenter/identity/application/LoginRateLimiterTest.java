package com.opscenter.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.opscenter.identity.domain.TooManyLoginAttemptsException;
import com.opscenter.identity.infrastructure.persistence.LoginAttemptRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** D-09: five failures inside the window block the sixth attempt; the window is sliding. */
@ExtendWith(MockitoExtension.class)
class LoginRateLimiterTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Mock LoginAttemptRepository attempts;

    @Test
    void belowThreshold_passes() {
        LoginRateLimiter limiter = new LoginRateLimiter(attempts, Clock.fixed(NOW, ZoneOffset.UTC), 5,
                Duration.ofMinutes(15));
        when(attempts.countFailuresSince("admin", NOW.minus(Duration.ofMinutes(15)))).thenReturn(4L);

        assertThatCode(() -> limiter.assertNotBlocked("admin")).doesNotThrowAnyException();
    }

    @Test
    void atThreshold_isBlockedWith429Code() {
        LoginRateLimiter limiter = new LoginRateLimiter(attempts, Clock.fixed(NOW, ZoneOffset.UTC), 5,
                Duration.ofMinutes(15));
        when(attempts.countFailuresSince("admin", NOW.minus(Duration.ofMinutes(15)))).thenReturn(5L);

        assertThatThrownBy(() -> limiter.assertNotBlocked("admin"))
                .isInstanceOf(TooManyLoginAttemptsException.class)
                .hasFieldOrPropertyWithValue("code", "AUTH_TOO_MANY_ATTEMPTS")
                .hasMessageContaining("5 in 15 minutes");
    }
}
