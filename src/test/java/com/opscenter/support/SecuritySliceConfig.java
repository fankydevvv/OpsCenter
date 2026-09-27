package com.opscenter.support;

import java.util.UUID;

import com.opscenter.shared.infrastructure.json.JsonConfig;
import com.opscenter.shared.infrastructure.security.ApiAccessDeniedHandler;
import com.opscenter.shared.infrastructure.security.ApiAuthenticationEntryPoint;
import com.opscenter.shared.infrastructure.security.JwtClaims;
import com.opscenter.shared.infrastructure.security.JwtConfig;
import com.opscenter.shared.infrastructure.security.PermissionJwtAuthenticationConverter;
import com.opscenter.shared.infrastructure.security.SecurityConfig;
import com.opscenter.shared.infrastructure.security.SecurityCurrentUser;
import com.opscenter.shared.infrastructure.web.ApiErrorWriter;
import com.opscenter.shared.infrastructure.web.ApiExceptionHandler;
import com.opscenter.shared.infrastructure.web.WebRequestContext;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Everything a {@code @WebMvcTest} slice needs to run the real security chain.
 * <p>
 * {@code @WebMvcTest} scans controllers, advice and filters but not plain {@code @Component}s or
 * {@code @Configuration}s, so the security wiring is imported explicitly here. It also contributes
 * a stand-in for the identity module's {@code SessionActiveJwtValidator}: any token whose
 * {@code sid} equals {@link #REVOKED_SESSION} is rejected with the {@code session_revoked} error,
 * which lets the shared kernel prove the 401 {@code AUTH_SESSION_REVOKED} path (TC-AUTH-005).
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({SecurityConfig.class, JwtConfig.class, JsonConfig.class, PermissionJwtAuthenticationConverter.class,
        ApiAuthenticationEntryPoint.class, ApiAccessDeniedHandler.class, ApiErrorWriter.class,
        ApiExceptionHandler.class, SecurityCurrentUser.class, WebRequestContext.class})
public class SecuritySliceConfig {

    public static final UUID REVOKED_SESSION = UUID.fromString("00000000-0000-4000-8000-00000000dead");

    @Bean
    OAuth2TokenValidator<Jwt> revokedSessionStubValidator() {
        return jwt -> REVOKED_SESSION.toString().equals(jwt.getClaimAsString(JwtClaims.SESSION_ID))
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error(JwtClaims.ERROR_SESSION_REVOKED,
                        "session revoked (test stub)", null))
                : OAuth2TokenValidatorResult.success();
    }
}
