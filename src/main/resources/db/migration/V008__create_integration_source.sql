-- ============================================================================
-- V008 - Inbound integration sources (03-DB §40.1, §22, §3.6; 04-API §23;
--        blueprint D-38, D-39)
--
-- integration_sources is the registry of systems that PUSH data into OpsCenter
-- (Alertmanager today; generic webhooks, Git/CI later). It is NOT the outbound
-- connector table "integrations" of 03-DB §20.1 (Telegram/SMTP), which arrives in
-- Sprint 3 together with notifications.
--
-- The shared secret of a source is never stored here: secret_ref only says where
-- the application finds it ("env:OPSCENTER_ALERTMANAGER_TOKEN").
-- ============================================================================

CREATE TABLE integration_sources (
    id                   UUID PRIMARY KEY,
    organization_id      UUID          NOT NULL REFERENCES organizations (id),
    code                 VARCHAR(80)   NOT NULL,
    name                 VARCHAR(255)  NOT NULL,
    source_type          VARCHAR(30)   NOT NULL
                         CHECK (source_type IN ('PROMETHEUS','ALERTMANAGER','GIT','CICD','ODOO','POSTGRESQL','LOG','GENERIC')),
    base_url             VARCHAR(1000) NULL,
    auth_type            VARCHAR(20)   NOT NULL
                         CHECK (auth_type IN ('NONE','BASIC','TOKEN','HMAC','MTLS','DB_READONLY')),
    secret_ref           VARCHAR(255)  NULL,
    enabled              BOOLEAN       NOT NULL DEFAULT TRUE,
    health_status        VARCHAR(20)   NOT NULL DEFAULT 'UNKNOWN'
                         CHECK (health_status IN ('UNKNOWN','CONNECTED','FAILED')),
    last_health_check_at TIMESTAMPTZ   NULL,
    last_event_at        TIMESTAMPTZ   NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by           UUID          NULL,
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by           UUID          NULL,
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uk_integration_sources_code UNIQUE (code)
);

COMMENT ON TABLE  integration_sources               IS 'Inbound integration registry (03-DB §40.1, D-38); outbound connectors (03-DB §20.1) come in Sprint 3.';
COMMENT ON COLUMN integration_sources.code          IS 'Path segment of the webhook: /api/v1/integrations/{code}/webhook.';
COMMENT ON COLUMN integration_sources.secret_ref    IS 'Reference only (env:NAME); the secret itself never enters the database (03-DB §3.6, D-39).';
COMMENT ON COLUMN integration_sources.last_event_at IS 'Last accepted inbound event (01-SRS §14 "Last synchronization"); best-effort, throttled (D-61).';

-- The one source of Sprint 2. Fixed id so documentation, tests and the demo can reference it.
INSERT INTO integration_sources (id, organization_id, code, name, source_type, auth_type, secret_ref, enabled)
VALUES ('00000000-0000-4000-8000-000000000301', '00000000-0000-4000-8000-000000000001',
        'alertmanager', 'Prometheus Alertmanager', 'ALERTMANAGER', 'TOKEN',
        'env:OPSCENTER_ALERTMANAGER_TOKEN', TRUE);

-- V004 / D-13 promised this foreign key once the integration table exists: an idempotency key
-- of a webhook delivery always belongs to a registered source (NULL = internal API call).
ALTER TABLE idempotency_keys
    ADD CONSTRAINT fk_idempotency_integration_source
    FOREIGN KEY (integration_id) REFERENCES integration_sources (id);
