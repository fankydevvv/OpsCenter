package com.opscenter.shared.application.storage;

import com.opscenter.shared.domain.DomainException;

import org.springframework.http.HttpStatus;

/**
 * The object store could not be reached or refused the operation -> HTTP 503
 * {@code OBJECT_STORAGE_UNAVAILABLE} (blueprint §7.7). The message is composed by the adapter and
 * never contains credentials.
 */
public class ObjectStorageUnavailableException extends DomainException {

    public static final String CODE = "OBJECT_STORAGE_UNAVAILABLE";

    public ObjectStorageUnavailableException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, CODE, message);
        if (cause != null) {
            initCause(cause);
        }
    }
}
