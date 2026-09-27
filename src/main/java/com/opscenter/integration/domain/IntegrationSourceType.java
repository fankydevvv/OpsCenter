package com.opscenter.integration.domain;

/** Kind of inbound source ({@code integration_sources.source_type}, 03-DB §40.1). */
public enum IntegrationSourceType {
    PROMETHEUS,
    ALERTMANAGER,
    GIT,
    CICD,
    ODOO,
    POSTGRESQL,
    LOG,
    GENERIC
}
