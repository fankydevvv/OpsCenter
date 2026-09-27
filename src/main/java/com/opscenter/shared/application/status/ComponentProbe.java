package com.opscenter.shared.application.status;

/**
 * One checkable component of the platform (backend, postgres, redis, rabbitmq, minio, prometheus,
 * alertmanager - D-63). Each adapter is a Spring bean; {@link SystemStatusService} collects all of
 * them, runs them in parallel and applies a common timeout, so a probe can be written as plain
 * blocking code.
 */
public interface ComponentProbe {

    /** Stable component name shown in the API ({@code postgres}, {@code redis} ...). */
    String name();

    /**
     * Checks the component. Throwing is allowed: the service turns any exception into
     * {@code DOWN} with a sanitised message.
     */
    ProbeResult probe() throws Exception;
}
