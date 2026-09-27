-- ============================================================================
-- V003 - Audit (03-DB §21, §29)
--
-- audit_logs is APPEND-ONLY: no business API updates or deletes a row, the JPA
-- entity has no setters, and there are no updated_* columns on purpose.
-- before_data/after_data are JSONB snapshots of DTOs (never entities) so a
-- password hash or token can never end up in the audit trail (03-DB §30).
-- ============================================================================

CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY,
    organization_id UUID NULL REFERENCES organizations (id),
    actor_id        UUID NULL REFERENCES users (id),
    action          VARCHAR(100) NOT NULL,
    resource_type   VARCHAR(100) NOT NULL,
    resource_id     UUID NULL,
    before_data     JSONB NULL,
    after_data      JSONB NULL,
    request_id      VARCHAR(100) NULL,
    source_ip       VARCHAR(64) NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_resource ON audit_logs (resource_type, resource_id, created_at DESC);
CREATE INDEX idx_audit_actor    ON audit_logs (actor_id, created_at DESC);
CREATE INDEX idx_audit_created  ON audit_logs (created_at DESC);

COMMENT ON TABLE  audit_logs            IS 'Append-only audit trail (03-DB §21). actor_id NULL = system or a failed login of an unknown user.';
COMMENT ON COLUMN audit_logs.action     IS 'Constant from com.opscenter.audit.domain.AuditAction, e.g. USER_LOCKED, ROLE_PERMISSIONS_CHANGED.';
COMMENT ON COLUMN audit_logs.request_id IS 'X-Request-Id of the HTTP request that caused the change (04-API §2.2).';
