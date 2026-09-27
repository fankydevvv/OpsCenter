package com.opscenter.alert.domain;

/**
 * Status of a logical alert (03-DB §11.1): {@code FIRING} while the source reports the problem,
 * {@code RESOLVED} once the source reports recovery (D-59). A resolved alert never fires again -
 * a new firing episode is a new row (REFIRED, D-47).
 */
public enum AlertStatus {
    FIRING,
    RESOLVED
}
