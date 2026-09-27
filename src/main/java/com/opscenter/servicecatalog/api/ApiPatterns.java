package com.opscenter.servicecatalog.api;

/**
 * Regular expressions of the request bodies (first line of defence, 400 {@code VALIDATION_FAILED}
 * with {@code fieldErrors}); the domain re-checks the same rules for callers that are not HTTP.
 */
final class ApiPatterns {

    /** Service code before lower-casing (D-33): the domain stores {@code Odoo-ERP} as {@code odoo-erp}. */
    static final String SERVICE_CODE = "^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$";
    static final String SERVICE_CODE_MESSAGE = "must start with a letter or digit and contain letters, digits, '.', '_' or '-' only";

    /** Environment code or alias before normalisation ({@code prod}, {@code Staging}, {@code pre-prod}). */
    static final String ENVIRONMENT_CODE = "^[A-Za-z][A-Za-z0-9_-]{1,49}$";
    static final String ENVIRONMENT_CODE_MESSAGE = "must start with a letter and contain letters, digits, '_' or '-' only (e.g. DEV, STAGING, PRODUCTION)";

    /** Absolute http(s) URL, or empty (= clear). Blocks {@code javascript:} links in the UI. */
    static final String HTTP_URL_OR_EMPTY = "^$|^[Hh][Tt][Tt][Pp][Ss]?://\\S+$";
    static final String HTTP_URL_MESSAGE = "must be an absolute http(s) URL";

    private ApiPatterns() {
    }
}
