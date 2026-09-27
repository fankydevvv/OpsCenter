package com.opscenter.shared.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP security of the API (04-API §18, 01-SRS FR-IAM-04, D-16).
 * <ul>
 *   <li><b>Stateless</b>: no HTTP session, no CSRF token - every request proves itself with a
 *       bearer JWT, which is why CSRF protection can be disabled safely.</li>
 *   <li><b>Public</b>: login/refresh (there is no token yet), liveness/readiness probes, the
 *       Prometheus scrape endpoint (the Dockerised Prometheus of 05-DEPLOY §15 cannot log in - R-11,
 *       to be hardened in Sprint 6) and the OpenAPI UI.</li>
 *   <li><b>Everything else</b> requires a valid token; fine-grained permissions are enforced per
 *       method with {@code @PreAuthorize("hasAuthority('<resource.action>')")}, enabled by
 *       {@link EnableMethodSecurity}. {@code /actuator/metrics} additionally needs the
 *       {@code ADMIN} role.</li>
 * </ul>
 * The OpenAPI paths are public only where springdoc is enabled; the container profile
 * {@code dev} switches springdoc off (see {@code application-dev.yml}).
 * Spring Security 7 only offers the lambda DSL; there is no {@code WebSecurityConfigurerAdapter}.
 * Business modules never touch this class - identity plugs in through beans
 * ({@code PasswordEncoder}, {@code OAuth2TokenValidator<Jwt>}).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Paths reachable without a token; kept as a constant so tests assert the exact list. */
    public static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                      PermissionJwtAuthenticationConverter jwtConverter,
                                                      ApiAuthenticationEntryPoint entryPoint,
                                                      ApiAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Raw metrics (every endpoint template, error rates, pool sizes) are for
                        // administrators, not for any logged-in engineer (05-DEPLOY §13).
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
