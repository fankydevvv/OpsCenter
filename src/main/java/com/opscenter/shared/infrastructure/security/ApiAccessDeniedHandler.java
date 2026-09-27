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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Answers 403 {@code RBAC_PERMISSION_DENIED} with the contract body when an authenticated caller
 * lacks a permission at the filter-chain level (URL rules). Method-level denials from
 * {@code @PreAuthorize} surface as exceptions inside MVC and are mapped by
 * {@code ApiExceptionHandler} to the very same body (TC-RBAC-001/003).
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessDeniedHandler.class);

    private final ApiErrorWriter writer;

    public ApiAccessDeniedHandler(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        log.debug("403 on {} {}: {}", request.getMethod(), request.getRequestURI(), exception.getMessage());
        writer.write(response, ApiErrors.of(HttpStatus.FORBIDDEN, ErrorCodes.RBAC_PERMISSION_DENIED,
                "You do not have permission to perform this action"));
    }
}
