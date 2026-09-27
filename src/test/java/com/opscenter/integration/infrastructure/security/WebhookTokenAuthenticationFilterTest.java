package com.opscenter.integration.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.opscenter.integration.application.IntegrationProperties;
import com.opscenter.integration.application.WebhookAuthenticator;
import com.opscenter.integration.domain.IntegrationAuthType;
import com.opscenter.integration.domain.IntegrationSource;
import com.opscenter.integration.domain.IntegrationSourceType;
import com.opscenter.integration.infrastructure.IntegrationSourceRepository;
import com.opscenter.shared.application.ratelimit.RateLimitDecision;
import com.opscenter.shared.application.ratelimit.RateLimiter;
import com.opscenter.shared.infrastructure.web.ApiErrorWriter;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review finding "unauthenticated DoS" on the webhook filter: requests to random
 * {@code /api/v1/integrations/<anything>/webhook} paths must neither create a new meter per path
 * segment, nor open a database transaction each, nor escape the per-address failure brake.
 */
class WebhookTokenAuthenticationFilterTest {

    private static final String TOKEN = "a-sufficiently-long-shared-token-0123456789";

    private final IntegrationSourceRepository sources = mock(IntegrationSourceRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CountingRateLimiter rateLimiter = new CountingRateLimiter();
    private WebhookTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        IntegrationSource source = mock(IntegrationSource.class);
        when(source.getId()).thenReturn(UUID.randomUUID());
        when(source.getCode()).thenReturn("alertmanager");
        when(source.getOrganizationId()).thenReturn(UUID.randomUUID());
        when(source.getSourceType()).thenReturn(IntegrationSourceType.ALERTMANAGER);
        when(source.getAuthType()).thenReturn(IntegrationAuthType.TOKEN);
        when(source.getSecretRef()).thenReturn("env:OPSCENTER_ALERTMANAGER_TOKEN");
        when(source.isEnabled()).thenReturn(true);
        when(sources.findAll()).thenReturn(List.of(source));

        WebhookAuthenticator authenticator = new WebhookAuthenticator(sources, ref -> Optional.of(TOKEN),
                Clock.systemUTC());
        IntegrationProperties properties = new IntegrationProperties(new IntegrationProperties.Alertmanager(
                null, 1024 * 1024, 500, Duration.ofMinutes(15), 600, 20));
        AuthFailureThrottle throttle = new AuthFailureThrottle(rateLimiter, properties, Clock.systemUTC());
        filter = new WebhookTokenAuthenticationFilter(authenticator, throttle,
                new ApiErrorWriter(JsonMapper.builder().build()), meters);
    }

    private MockHttpServletResponse call(String sourceSegment, String clientIp, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/v1/integrations/" + sourceSegment + "/webhook");
        request.setRemoteAddr(clientIp);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void randomSourcePaths_areAnswered401_withoutNewMetersOrDatabaseReads() throws Exception {
        for (int i = 0; i < 15; i++) {
            MockHttpServletResponse response = call("scan-" + UUID.randomUUID(), "10.0.0." + i, TOKEN);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("INTEGRATION_AUTH_FAILED");
        }

        Set<String> sourceTags = meters.find("opscenter.webhook.requests").meters().stream()
                .map(meter -> meter.getId().getTag("source"))
                .collect(Collectors.toSet());
        assertThat(sourceTags).containsExactly(WebhookAuthenticator.UNKNOWN_SOURCE);
        verify(sources, times(1)).findAll();
    }

    @Test
    void scanningUnknownSources_isThrottledPerAddress_likeWrongTokens() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(call("scan-" + i, "192.0.2.7", null).getStatus()).isEqualTo(401);
        }
        MockHttpServletResponse limited = call("scan-20", "192.0.2.7", null);

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        // blocked before the comparison - even the right token of a real source waits
        assertThat(call("alertmanager", "192.0.2.7", TOKEN).getStatus()).isEqualTo(429);
        // other addresses are not affected
        assertThat(call("alertmanager", "192.0.2.8", TOKEN).getStatus()).isEqualTo(200);
    }

    @Test
    void theRightToken_passes_andIsTaggedWithTheRegisteredCode() throws Exception {
        MockHttpServletResponse response = call("alertmanager", "10.1.1.1", TOKEN);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(meters.find("opscenter.webhook.requests").meters()).map(Meter::getId)
                .allSatisfy(id -> assertThat(id.getTag("source")).isIn("alertmanager", WebhookAuthenticator.UNKNOWN_SOURCE));
    }

    /** In-memory fixed window: counts per bucket, never resets (the test runs within one window). */
    private static final class CountingRateLimiter implements RateLimiter {

        private final Map<String, Long> hits = new HashMap<>();

        @Override
        public synchronized RateLimitDecision tryAcquire(String bucket, int limit, Duration window) {
            long count = hits.merge(bucket, 1L, Long::sum);
            return new RateLimitDecision(count <= limit, count, limit, Duration.ofSeconds(30), false);
        }
    }
}
