package com.opscenter.integration.infrastructure.security;

import com.opscenter.integration.application.WebhookAuthenticator;
import com.opscenter.integration.domain.IntegrationErrorCodes;
import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.infrastructure.web.ApiErrorWriter;
import com.opscenter.shared.infrastructure.web.ApiErrors;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * A second, dedicated {@link SecurityFilterChain} for inbound webhooks (blueprint D-40).
 * <p>
 * Why not simply add the webhook path to the {@code permitAll} list of the main chain? Because the
 * main chain is an OAuth2 resource server: its {@code BearerTokenAuthenticationFilter} reads every
 * {@code Authorization: Bearer ...} header and tries to decode it as a JWT - Alertmanager's shared
 * token is not a JWT, so the request would be answered 401 before any {@code permitAll} rule is
 * consulted. Spring Security's answer is "one chain per kind of client": this chain matches only
 * {@code /api/v1/integrations/*}{@code /webhook}, is consulted first ({@code @Order(1)}; the main
 * chain has no order = last), has no JWT support at all, and replaces it with
 * {@link WebhookTokenAuthenticationFilter}. The main {@code SecurityConfig} stays untouched, and a
 * user's valid JWT is <em>not</em> accepted here (it is not the source's token).
 * <p>
 * The authorization rule {@code hasAuthority(INTEGRATION_WEBHOOK)} is a second lock behind the
 * filter: even if the filter were misconfigured, an anonymous request could not reach the controller.
 */
@Configuration(proxyBeanMethods = false)
public class IntegrationWebhookSecurityConfig {

    /** The only paths of this chain; {@code *} = the integration source code. */
    public static final String WEBHOOK_PATHS = "/api/v1/integrations/*/webhook";
    /** The single authority an authenticated integration source holds. */
    public static final String AUTHORITY = "INTEGRATION_WEBHOOK";

    @Bean
    @Order(1)
    public SecurityFilterChain integrationWebhookSecurityFilterChain(HttpSecurity http,
                                                                     WebhookAuthenticator authenticator,
                                                                     AuthFailureThrottle throttle,
                                                                     ApiErrorWriter errors,
                                                                     ObjectProvider<MeterRegistry> meters)
            throws Exception {
        WebhookTokenAuthenticationFilter tokenFilter = new WebhookTokenAuthenticationFilter(authenticator, throttle,
                errors, meters.getIfAvailable(SimpleMeterRegistry::new));
        http
                .securityMatcher(WEBHOOK_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(tokenFilter, AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(AUTHORITY))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> errors.write(response,
                                ApiErrors.of(HttpStatus.UNAUTHORIZED, IntegrationErrorCodes.INTEGRATION_AUTH_FAILED,
                                        "Missing or invalid integration token")))
                        .accessDeniedHandler((request, response, ex) -> errors.write(response,
                                ApiErrors.of(HttpStatus.FORBIDDEN, ErrorCodes.RBAC_PERMISSION_DENIED,
                                        "Not allowed for this integration source"))));
        return http.build();
    }
}
