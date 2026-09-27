package com.opscenter.shared.infrastructure.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.infrastructure.web.ApiErrorWriter;
import com.opscenter.shared.infrastructure.web.ApiErrors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Answers 401 with the contract body when a request has no usable bearer token (D-18).
 * <p>
 * Spring Security's default {@code BearerTokenAuthenticationEntryPoint} only sets a
 * {@code WWW-Authenticate} header with an empty body; the API promises JSON. Two codes are
 * distinguished because the client reacts differently: {@code AUTH_UNAUTHENTICATED} (missing,
 * expired or tampered token -> try a refresh) versus {@code AUTH_SESSION_REVOKED} (logout/lock ->
 * go back to login, TC-AUTH-005). The latter is detected from the {@code session_revoked} error the
 * identity module's validator attaches to the {@link JwtValidationException}.
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthenticationEntryPoint.class);

    private final ApiErrorWriter writer;

    public ApiAuthenticationEntryPoint(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        boolean revoked = isSessionRevoked(exception);
        String code = revoked ? ErrorCodes.AUTH_SESSION_REVOKED : ErrorCodes.AUTH_UNAUTHENTICATED;
        String message = revoked ? "Session has been revoked; please sign in again"
                : "Authentication required: missing, expired or invalid access token";
        log.debug("401 {} on {} {}: {}", code, request.getMethod(), request.getRequestURI(), exception.getMessage());
        response.setHeader("WWW-Authenticate", "Bearer realm=\"opscenter\"");
        writer.write(response, ApiErrors.of(HttpStatus.UNAUTHORIZED, code, message));
    }

    static boolean isSessionRevoked(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof JwtValidationException validation) {
                for (OAuth2Error error : validation.getErrors()) {
                    if (JwtClaims.ERROR_SESSION_REVOKED.equals(error.getErrorCode())) {
                        return true;
                    }
                }
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }
}
