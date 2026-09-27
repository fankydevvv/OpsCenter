-- ============================================================================
-- V004 - Reliability: idempotency keys and transactional outbox (03-DB §22, §23)
--
-- idempotency_keys : one row per (integration, Idempotency-Key). integration_id is
--                    NULL for internal API calls, so the unique constraint uses
--                    NULLS NOT DISTINCT (PostgreSQL >= 15) to make NULL behave
--                    like a real value. The FK to integrations is added by the
--                    integration migration once that table exists.
-- outbox_events    : domain events written in the SAME transaction as the state
--                    change; OutboxRelay publishes them to RabbitMQ afterwards.
-- Both tables are purged by a retention job (03-DB §29), never soft-deleted.
-- ============================================================================

CREATE TABLE idempotency_keys (
    id              UUID PRIMARY KEY,
    integration_id  UUID NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(128) NULL,
    resource_type   VARCHAR(100) NULL,
    resource_id     UUID NULL,
    status          VARCHAR(30) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    response_code   INT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NULL,
    CONSTRAINT uk_idempotency_integration_key UNIQUE NULLS NOT DISTINCT (integration_id, idempotency_key)
);
CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);

COMMENT ON TABLE  idempotency_keys              IS 'Protects inbound endpoints from double processing (04-API §2.5, 03-DB §22).';
COMMENT ON COLUMN idempotency_keys.request_hash IS 'SHA-256 of the canonical request body; same key + different hash = 422 IDEMPOTENCY_KEY_REUSED (D-13).';

CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   UUID NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ NULL,
    status         VARCHAR(30) NOT NULL CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    retry_count    INT NOT NULL DEFAULT 0,
    last_error     TEXT NULL
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (status, occurred_at);

COMMENT ON TABLE  outbox_events            IS 'Transactional outbox (03-DB §23, 04-API §15): exchange opscenter.events, routing key derived from event_type.';
COMMENT ON COLUMN outbox_events.status     IS 'PENDING -> PUBLISHED, or FAILED after opscenter.outbox.relay.max-retries attempts (D-14).';
