package com.opscenter.alert.domain;

import com.opscenter.shared.domain.DomainException;

import org.springframework.http.HttpStatus;

/**
 * 503 {@code ALERT_INGESTION_BUSY}: another delivery of the same alert group held the group lock
 * longer than {@code opscenter.alert.lock.wait} (D-50). 503 is the honest answer - the condition is
 * temporary - and Alertmanager retries 5xx answers on its own, so nothing is lost.
 */
public class AlertIngestionBusyException extends DomainException {

    public AlertIngestionBusyException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, AlertErrorCodes.ALERT_INGESTION_BUSY, message);
    }
}
