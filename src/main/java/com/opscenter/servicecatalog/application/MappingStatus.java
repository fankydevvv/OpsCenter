package com.opscenter.servicecatalog.application;

/**
 * Whether an alert could be attached to a catalog service (FR-ALT-05, blueprint D-48).
 * {@code UNMAPPED} alerts are not dropped: they still open an incident without service/team so that
 * no alert is lost (D-49, TC-ROUTE-003).
 */
public enum MappingStatus {
    MAPPED,
    UNMAPPED
}
