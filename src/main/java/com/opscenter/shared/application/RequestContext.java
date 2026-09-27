package com.opscenter.shared.application;

import java.util.Optional;

/**
 * Port exposing the technical context of the current request (correlation id, client IP) to
 * application services that must persist it - typically the audit recorder (03-DB §21
 * {@code request_id}, {@code source_ip}).
 * <p>
 * Keeping this behind an interface means the application layer does not depend on the servlet API
 * and returns empty values when running outside an HTTP request (scheduled jobs, tests).
 */
public interface RequestContext {

    /** The {@code X-Request-Id} of the current request (04-API §2.2). */
    Optional<String> requestId();

    /**
     * Remote address of the caller as seen by the servlet container. {@code X-Forwarded-For} is
     * honoured only through Tomcat's trusted-proxy configuration, never parsed by hand.
     */
    Optional<String> clientIp();
}
