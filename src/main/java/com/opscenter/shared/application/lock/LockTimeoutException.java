package com.opscenter.shared.application.lock;

import java.time.Duration;

import com.opscenter.shared.domain.DomainException;

import org.springframework.http.HttpStatus;

/**
 * Another holder kept the lock longer than the caller was willing to wait -> 503: the condition is
 * transient and the client (Alertmanager retries on 5xx) should simply try again. Modules usually
 * catch it and rethrow their own code, e.g. {@code ALERT_INGESTION_BUSY} (blueprint D-50).
 */
public class LockTimeoutException extends DomainException {

    public static final String CODE = "LOCK_TIMEOUT";

    private final String key;

    public LockTimeoutException(String key, Duration waited) {
        super(HttpStatus.SERVICE_UNAVAILABLE, CODE,
                "Resource is busy (lock '" + key + "' not acquired within " + waited.toMillis() + " ms). Retry shortly.");
        this.key = key;
    }

    public String key() {
        return key;
    }
}
