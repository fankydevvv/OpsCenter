package com.opscenter.integration.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.integration.domain.IntegrationAuthType;
import com.opscenter.integration.domain.IntegrationSource;
import com.opscenter.integration.domain.IntegrationSourceType;
import com.opscenter.integration.infrastructure.IntegrationSourceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-39: constant-time token check, "misconfigured" (503) kept apart from "wrong token" (401), and -
 * after the DoS review - no database round trip and no unbounded metric tag per unauthenticated
 * request.
 */
class WebhookAuthenticatorTest {

    private static final String TOKEN = "a-sufficiently-long-shared-token-0123456789";
    private static final UUID SOURCE_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();

    private final IntegrationSourceRepository sources = mock(IntegrationSourceRepository.class);
    private final IntegrationSource source = mock(IntegrationSource.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-27T10:00:00Z"));
    private String configuredSecret = TOKEN;
    private final WebhookAuthenticator authenticator = new WebhookAuthenticator(sources,
            ref -> Optional.ofNullable(configuredSecret), clock);

    @BeforeEach
    void registeredSource() {
        when(source.getId()).thenReturn(SOURCE_ID);
        when(source.getCode()).thenReturn("alertmanager");
        when(source.getOrganizationId()).thenReturn(ORG_ID);
        when(source.getSourceType()).thenReturn(IntegrationSourceType.ALERTMANAGER);
        when(source.getAuthType()).thenReturn(IntegrationAuthType.TOKEN);
        when(source.getSecretRef()).thenReturn("env:OPSCENTER_ALERTMANAGER_TOKEN");
        when(source.isEnabled()).thenReturn(true);
        when(sources.findAll()).thenReturn(List.of(source));
    }

    @Test
    void rightToken_authenticatesAsTheSource() {
        WebhookAuthenticator.Result result = authenticator.authenticate("alertmanager", TOKEN);

        assertThat(result.outcome()).isEqualTo(WebhookAuthenticator.Outcome.AUTHENTICATED);
        assertThat(result.source()).isEqualTo(new IntegrationSourceRef(SOURCE_ID, "alertmanager", ORG_ID,
                IntegrationSourceType.ALERTMANAGER));
        assertThat(result.metricSource()).isEqualTo("alertmanager");
    }

    @Test
    void wrongOrMissingToken_isRejected() {
        assertThat(authenticator.authenticate("alertmanager", TOKEN + "x").outcome())
                .isEqualTo(WebhookAuthenticator.Outcome.REJECTED);
        assertThat(authenticator.authenticate("alertmanager", TOKEN.substring(1)).outcome())
                .isEqualTo(WebhookAuthenticator.Outcome.REJECTED);
        WebhookAuthenticator.Result missing = authenticator.authenticate("alertmanager", null);
        assertThat(missing.outcome()).isEqualTo(WebhookAuthenticator.Outcome.REJECTED);
        assertThat(missing.metricSource()).isEqualTo("alertmanager");
    }

    @Test
    void unknownSource_isRejectedLikeAWrongToken_withABoundedMetricTag() {
        WebhookAuthenticator.Result result = authenticator.authenticate("nagios-" + UUID.randomUUID(), TOKEN);

        assertThat(result.outcome()).isEqualTo(WebhookAuthenticator.Outcome.REJECTED);
        assertThat(result.metricSource()).isEqualTo(WebhookAuthenticator.UNKNOWN_SOURCE);
        assertThat(result.message()).isEqualTo(authenticator.authenticate("alertmanager", "wrong").message());
        assertThat(authenticator.authenticate(null, TOKEN).metricSource()).isEqualTo(WebhookAuthenticator.UNKNOWN_SOURCE);
    }

    @Test
    void disabledSource_isUnavailable_withAGenericMessage() {
        when(source.isEnabled()).thenReturn(false);

        WebhookAuthenticator.Result result = authenticator.authenticate("alertmanager", TOKEN);

        assertThat(result.outcome()).isEqualTo(WebhookAuthenticator.Outcome.UNAVAILABLE);
        assertThat(result.metricSource()).isEqualTo("alertmanager");
        assertThat(result.message()).doesNotContain("disabled").doesNotContain("alertmanager");
        assertThat(result.reason()).contains("disabled");
    }

    @Test
    void missingShortOrPlaceholderSecret_isUnavailable_evenForTheMatchingToken() {
        configuredSecret = null;
        assertThat(authenticator.authenticate("alertmanager", null).outcome())
                .isEqualTo(WebhookAuthenticator.Outcome.UNAVAILABLE);
        configuredSecret = "short-token";
        assertThat(authenticator.authenticate("alertmanager", "short-token").outcome())
                .isEqualTo(WebhookAuthenticator.Outcome.UNAVAILABLE);
        configuredSecret = "change-me-please-with-a-long-enough-value";
        WebhookAuthenticator.Result placeholder = authenticator.authenticate("alertmanager", configuredSecret);
        assertThat(placeholder.outcome()).isEqualTo(WebhookAuthenticator.Outcome.UNAVAILABLE);
        assertThat(placeholder.message()).doesNotContain(configuredSecret);
        assertThat(placeholder.reason()).doesNotContain(configuredSecret);
    }

    @Test
    void otherAuthType_isUnavailable() {
        when(source.getAuthType()).thenReturn(IntegrationAuthType.HMAC);
        assertThat(authenticator.authenticate("alertmanager", TOKEN).outcome())
                .isEqualTo(WebhookAuthenticator.Outcome.UNAVAILABLE);
    }

    @Test
    void theRegistryIsReadOnceForManyRequests_andAgainOnlyAfterItsTtl() {
        for (int i = 0; i < 50; i++) {
            authenticator.authenticate("random-" + i, "x");
            authenticator.authenticate("alertmanager", TOKEN);
        }
        verify(sources, times(1)).findAll();

        clock.advance(WebhookAuthenticator.SOURCE_CACHE_TTL.plusSeconds(1));
        authenticator.authenticate("alertmanager", TOKEN);
        verify(sources, times(2)).findAll();

        authenticator.invalidate();
        authenticator.authenticate("alertmanager", TOKEN);
        verify(sources, times(3)).findAll();
    }

    @Test
    void aFailedRefresh_keepsServingThePreviousCopy() {
        authenticator.authenticate("alertmanager", TOKEN);
        clock.advance(WebhookAuthenticator.SOURCE_CACHE_TTL.plusSeconds(1));
        when(sources.findAll()).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThat(authenticator.authenticate("alertmanager", TOKEN).outcome())
                .isEqualTo(WebhookAuthenticator.Outcome.AUTHENTICATED);
    }

    @Test
    void tokensMatch_comparesDigests() {
        assertThat(WebhookAuthenticator.tokensMatch(TOKEN, TOKEN)).isTrue();
        assertThat(WebhookAuthenticator.tokensMatch(TOKEN, TOKEN.toUpperCase())).isFalse();
        assertThat(WebhookAuthenticator.tokensMatch("", TOKEN)).isFalse();
        assertThat(WebhookAuthenticator.tokensMatch(null, TOKEN)).isFalse();
    }

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
