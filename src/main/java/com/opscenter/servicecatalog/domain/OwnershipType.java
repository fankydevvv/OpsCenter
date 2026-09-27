package com.opscenter.servicecatalog.domain;

/**
 * Role of an <em>additional</em> owner in {@code service_owners} (blueprint D-34). The main owners
 * (owning team, primary and backup user) are columns of {@code services}; these rows add the
 * supporting teams and contacts that routing and notification (Sprint 3) may fall back to.
 */
public enum OwnershipType {
    SUPPORTING_TEAM,
    TECHNICAL_OWNER,
    BUSINESS_OWNER,
    ON_CALL_CONTACT
}
