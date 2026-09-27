package com.opscenter.servicecatalog.application;

/**
 * Service code + environment extracted from alert labels and normalised (D-33, D-44):
 * {@code {service="Odoo-ERP", env="prod"}} becomes {@code ("odoo-erp", "PRODUCTION")}. Either part
 * may be {@code null} when the labels do not carry it. The alert module uses these normalised values
 * for the fingerprint as well, so "prod" and "PRODUCTION" can never produce two fingerprints.
 */
public record ServiceKey(String serviceCode, String environment) {
}
