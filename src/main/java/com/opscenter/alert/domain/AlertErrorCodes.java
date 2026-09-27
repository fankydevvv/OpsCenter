package com.opscenter.alert.domain;

/** Error codes of the alert module (blueprint §7.7). */
public final class AlertErrorCodes {

    /** 404 */
    public static final String ALERT_NOT_FOUND = "ALERT_NOT_FOUND";
    /** 404 - the alert has no archived raw webhook (archive failed or storage disabled). */
    public static final String ALERT_RAW_PAYLOAD_UNAVAILABLE = "ALERT_RAW_PAYLOAD_UNAVAILABLE";
    /** 503 - the alert group's lock was busy for longer than the wait time; Alertmanager retries (D-50). */
    public static final String ALERT_INGESTION_BUSY = "ALERT_INGESTION_BUSY";

    private AlertErrorCodes() {
    }
}
