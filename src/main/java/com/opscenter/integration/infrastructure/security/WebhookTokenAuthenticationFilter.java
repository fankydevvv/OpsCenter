package com.opscenter.integration.infrastructure.security;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.opscenter.integration.application.WebhookAuthenticator;
import com.opscenter.integration.domain.IntegrationErrorCodes;
import com.opscenter.shared.infrastructure.web.ApiErrorWriter;
import com.opscenter.shared.infrastructure.web.ApiErrors;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code POST /api/v1/integrations/{source}/webhook} with the source's shared bearer
 * token (blueprint D-39, D-40). Runs only inside the dedicated webhook filter chain.
 * <ol>
 *   <li>address blocked after too many failures -> 429 (the token is not even compared);</li>
 *   <li>path names no registered source, or token missing/wrong -> 401 {@code INTEGRATION_AUTH_FAILED}
 *       - logged at WARN with the (registered) source and the address, <b>never the token</b>, and
 *       not audited (flood protection);</li>
 *   <li>registered source disabled/misconfigured -> 503 {@code INTEGRATION_UNAVAILABLE} with a generic
 *       message (the precise reason stays in the server log);</li>
 *   <li>token right -> an {@link IntegrationAuthenticationToken} is put into the security context
 *       and the request continues to the controller.</li>
 * </ol>
 * Every answer that is not "authenticated" counts as a failure of the client address in
 * {@link AuthFailureThrottle}: scanning random source paths is braked exactly like guessing tokens.
 * The metric {@code opscenter.webhook.requests} is tagged with {@link WebhookAuthenticator.Result#metricSource()}
 * - a registered code or {@code unknown} - never with the raw, attacker-controlled path segment
 * (each distinct tag value would be a new meter kept in memory forever).
 * <p>
 * The filter writes the error bodies itself (same {@code ApiError} shape as everywhere, D-18) because
 * errors raised inside the filter chain never reach {@code @RestControllerAdvice}. It is created by
 * {@link IntegrationWebhookSecurityConfig}, deliberately not a Spring bean: a {@code Filter} bean
 * would be auto-registered by Spring Boot for <em>every</em> request.
 */
public class WebhookTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebhookTokenAuthenticationFilter.class);

    private static final Pattern WEBHOOK_PATH = Pattern.compile("^/api/v1/integrations/([^/]+)/webhook/?$");
    private static final String BEARER_PREFIX = "bearer ";

    private final WebhookAuthenticator authenticator;
    private final AuthFailureThrottle throttle;
    private final ApiErrorWriter errors;
    private final MeterRegistry meters;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public WebhookTokenAuthenticationFilter(WebhookAuthenticator authenticator, AuthFailureThrottle throttle,
                                            ApiErrorWriter errors, MeterRegistry meters) {
        this.authenticator = authenticator;
        this.throttle = throttle;
        this.errors = errors;
        this.meters = meters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sourceCode = sourceCodeOf(request);
        String clientIp = request.getRemoteAddr();

        if (throttle.isBlocked(clientIp)) {
            // Not resolved yet and never the raw path segment: the tag must stay bounded.
            count(WebhookAuthenticator.UNKNOWN_SOURCE, "auth_blocked");
            tooManyFailures(response, clientIp);
            return;
        }

        String presented = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        WebhookAuthenticator.Result result = authenticator.authenticate(sourceCode, presented);
        String source = result.metricSource();
        switch (result.outcome()) {
            case UNAVAILABLE -> {
                count(source, "unavailable");
                log.warn("Webhook for source '{}' from {} refused: {}", source, clientIp, result.reason());
                if (throttle.recordFailure(clientIp)) {
                    tooManyFailures(response, clientIp);
                }
                else {
                    errors.write(response, ApiErrors.of(HttpStatus.SERVICE_UNAVAILABLE,
                            IntegrationErrorCodes.INTEGRATION_UNAVAILABLE, result.message()));
                }
            }
            case REJECTED -> {
                count(source, "auth_failed");
                log.warn("Rejected webhook for source '{}' from {}: {}", source, clientIp, result.reason());
                if (throttle.recordFailure(clientIp)) {
                    tooManyFailures(response, clientIp);
                }
                else {
                    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"opscenter-integrations\"");
                    errors.write(response, ApiErrors.of(HttpStatus.UNAUTHORIZED,
                            IntegrationErrorCodes.INTEGRATION_AUTH_FAILED, result.message()));
                }
            }
            case AUTHENTICATED -> {
                SecurityContext context = contextHolder.createEmptyContext();
                context.setAuthentication(new IntegrationAuthenticationToken(result.source()));
                contextHolder.setContext(context);
                chain.doFilter(request, response);
            }
        }
    }

    private void tooManyFailures(HttpServletResponse response, String clientIp) throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(throttle.retryAfterSeconds(clientIp)));
        errors.write(response, ApiErrors.of(HttpStatus.TOO_MANY_REQUESTS, IntegrationErrorCodes.INTEGRATION_RATE_LIMITED,
                "Too many failed authentications from this address; retry later"));
    }

    /** {@code alertmanager} from {@code /api/v1/integrations/alertmanager/webhook} (context path stripped). */
    static String sourceCodeOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        Matcher matcher = WEBHOOK_PATH.matcher(uri);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** The value after {@code Bearer } (scheme case-insensitive, RFC 6750), or {@code null}. */
    static String bearerToken(String authorization) {
        if (authorization == null || authorization.length() <= BEARER_PREFIX.length()
                || !authorization.substring(0, BEARER_PREFIX.length()).toLowerCase(Locale.ROOT).equals(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** @param source a registered source code or {@code unknown} - never request text (bounded cardinality) */
    private void count(String source, String result) {
        Counter.builder("opscenter.webhook.requests")
                .description("Inbound webhook deliveries by result (blueprint §9.4)")
                .tag("source", source)
                .tag("result", result)
                .register(meters)
                .increment();
    }
}
