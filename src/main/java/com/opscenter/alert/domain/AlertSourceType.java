package com.opscenter.alert.domain;

/** Which kind of source produced an alert ({@code alerts.source_type}, 03-DB §11.1). */
public enum AlertSourceType {
    ALERTMANAGER,
    WEBHOOK,
    API,
    MANUAL
}
