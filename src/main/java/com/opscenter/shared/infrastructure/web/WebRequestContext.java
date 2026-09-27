package com.opscenter.shared.infrastructure.web;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import com.opscenter.shared.application.RequestContext;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Servlet-based {@link RequestContext}: request id from the MDC, client IP from the current request.
 * <p>
 * The IP is <b>always</b> {@code request.getRemoteAddr()}, i.e. the TCP peer. {@code X-Forwarded-For}
 * is never parsed here on purpose: anybody can send that header, and a forged value would end up
 * in {@code audit_logs.source_ip}, {@code login_attempts.ip_address} and {@code user_sessions}
 * (03-DB §21, 01-SRS §15). When a trusted reverse proxy exists (nginx, 05-DEPLOY §13), Tomcat's
 * {@code RemoteIpValve} rewrites {@code getRemoteAddr()} from the header - but only for peers
 * listed in {@code server.tomcat.remoteip.internal-proxies} (see {@code application.yml}). That
 * keeps the trust decision in configuration, next to the deployment, instead of in code.
 * Returns empty values on non-request threads (scheduler, tests without MockMvc).
 */
@Component
public class WebRequestContext implements RequestContext {

    private static final int MAX_IP_LENGTH = 64;

    @Override
    public Optional<String> requestId() {
        return Optional.ofNullable(RequestIdFilter.currentRequestId());
    }

    @Override
    public Optional<String> clientIp() {
        return currentRequest().map(WebRequestContext::clientIpOf);
    }

    private static Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return Optional.ofNullable(attrs.getRequest());
        }
        return Optional.empty();
    }

    static String clientIpOf(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        if (address == null) {
            return null;
        }
        return address.length() > MAX_IP_LENGTH ? address.substring(0, MAX_IP_LENGTH) : address;
    }
}
