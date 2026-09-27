-- ============================================================================
-- V006 - Review fixes of the base (blueprint D-26, D-28, R-18)
--
-- 1. user.delete: DELETE /api/v1/users/{id} (soft delete, D-26) gets its own
--    permission instead of borrowing user.lock. Granted to ADMIN only.
-- 2. outbox_events.next_attempt_at: exponential backoff for the relay (D-28)
--    so a broker outage does not park every event as FAILED within seconds.
-- 3. refresh_tokens (user_id, revoked_at): the bulk revocation on account
--    lock/delete filters by user and would otherwise scan the table.
-- ============================================================================

INSERT INTO permissions (id, code, resource, action) VALUES
    ('00000000-0000-4000-8000-000000001016', 'user.delete', 'user', 'delete');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'user.delete'
WHERE r.code = 'ADMIN';

ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();
UPDATE outbox_events SET next_attempt_at = occurred_at;

DROP INDEX IF EXISTS idx_outbox_unpublished;
CREATE INDEX idx_outbox_unpublished ON outbox_events (status, next_attempt_at);

COMMENT ON COLUMN outbox_events.next_attempt_at IS 'Earliest time the relay may (re)try; pushed out exponentially after each failure (D-28).';

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id, revoked_at);
