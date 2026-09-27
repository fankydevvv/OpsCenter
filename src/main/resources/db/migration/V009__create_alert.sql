-- ============================================================================
-- V009 - Alert Management (01-SRS §5 FR-ALT-01..05; 03-DB §11, §25, §30;
--        blueprint D-42..D-49, D-61)
--
-- alerts            : one row per LOGICAL alert = one "firing episode" of a
--                     fingerprint. Repeated notifications of the same episode only
--                     increment occurrence_count (dedup, TC-DEDUP-001); an alert
--                     that fires again after it was resolved becomes a NEW row
--                     (REFIRED), so MTTR and dedup rate stay meaningful.
-- alert_occurrences : append-only log of every notification that touched an alert
--                     (which delivery, what the ingestion decided). A replayed
--                     webhook rebuilds its answer from these rows (D-43).
--
-- The raw webhook body is NOT stored here: it lives in object storage and the
-- JSON columns only keep a reference {bucket, key, sha256, sizeBytes} plus a small
-- masked slice of the alert (03-DB §3.5, §30; D-42).
-- ============================================================================

CREATE TABLE alerts (
    id                    UUID PRIMARY KEY,
    organization_id       UUID         NOT NULL REFERENCES organizations (id),
    service_id            UUID         NULL REFERENCES services (id),
    integration_source_id UUID         NULL REFERENCES integration_sources (id),
    source_type           VARCHAR(50)  NOT NULL CHECK (source_type IN ('ALERTMANAGER','WEBHOOK','API','MANUAL')),
    external_alert_id     VARCHAR(255) NULL,
    alert_name            VARCHAR(255) NOT NULL,
    fingerprint           VARCHAR(128) NOT NULL,
    severity              VARCHAR(20)  NOT NULL CHECK (severity IN ('P1','P2','P3','P4')),
    service_code          VARCHAR(100) NULL,
    environment           VARCHAR(50)  NULL,
    instance              VARCHAR(255) NULL,
    summary               TEXT         NULL,
    status                VARCHAR(30)  NOT NULL CHECK (status IN ('FIRING','RESOLVED')),
    first_seen_at         TIMESTAMPTZ  NOT NULL,
    last_seen_at          TIMESTAMPTZ  NOT NULL,
    resolved_at           TIMESTAMPTZ  NULL,
    occurrence_count      BIGINT       NOT NULL DEFAULT 1 CHECK (occurrence_count >= 1),
    raw_payload           JSONB        NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by            UUID         NULL,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by            UUID         NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_alerts_resolved_at CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

CREATE INDEX idx_alerts_fingerprint_status ON alerts (fingerprint, status);
-- D-47 / D-51: at most ONE firing alert per fingerprint. The Redis lock makes the race rare,
-- this index makes it impossible (the losing transaction retries and takes the dedup path).
CREATE UNIQUE INDEX uk_alerts_firing_fingerprint ON alerts (organization_id, fingerprint) WHERE status = 'FIRING';
CREATE INDEX idx_alerts_service_time  ON alerts (service_id, first_seen_at DESC);
CREATE INDEX idx_alerts_severity_time ON alerts (severity, first_seen_at DESC);
CREATE INDEX idx_alerts_last_seen     ON alerts (last_seen_at DESC);
-- D-48: the "unmapped alerts" work queue of the Operations Center.
CREATE INDEX idx_alerts_unmapped      ON alerts (last_seen_at DESC) WHERE service_id IS NULL;

COMMENT ON COLUMN alerts.fingerprint           IS 'sha256(alertName, service, ENV, instance) - never a timestamp (FR-ALT-03, D-46).';
COMMENT ON COLUMN alerts.external_alert_id     IS 'Fingerprint computed by Alertmanager over ALL labels; informative only, not used for dedup (D-46).';
COMMENT ON COLUMN alerts.service_code          IS 'Service code as reported by the source; kept for unmapped alerts so an admin sees what to register (D-48, D-61).';
COMMENT ON COLUMN alerts.integration_source_id IS 'Which inbound source delivered the alert (D-61).';
COMMENT ON COLUMN alerts.resolved_at           IS 'endsAt of the resolved notification (or server time); NULL while FIRING (D-59, D-61).';
COMMENT ON COLUMN alerts.raw_payload           IS 'Per-alert slice (masked labels/annotations) + rawRef to the archived webhook in object storage (D-42).';

CREATE TABLE alert_occurrences (
    id              UUID PRIMARY KEY,
    alert_id        UUID         NOT NULL REFERENCES alerts (id),
    observed_at     TIMESTAMPTZ  NOT NULL,
    status          VARCHAR(30)  NOT NULL CHECK (status IN ('FIRING','RESOLVED')),
    source_event_id VARCHAR(255) NULL,
    payload         JSONB        NULL
);

CREATE INDEX idx_alert_occurrences_alert_time   ON alert_occurrences (alert_id, observed_at DESC);
CREATE INDEX idx_alert_occurrences_source_event ON alert_occurrences (source_event_id);

COMMENT ON TABLE  alert_occurrences                 IS 'Append-only: one row per notification that touched an alert (03-DB §11.2, §29).';
COMMENT ON COLUMN alert_occurrences.observed_at     IS 'Server receive time - the source clock is not trusted for ordering (R-33).';
COMMENT ON COLUMN alert_occurrences.status          IS 'Status reported by this notification (D-61).';
COMMENT ON COLUMN alert_occurrences.source_event_id IS 'Delivery id = idempotency resource_id; lets a replay rebuild its response (D-43).';
COMMENT ON COLUMN alert_occurrences.payload         IS 'Ingestion decision (outcome, incident link) + rawRef of the archived delivery; no secrets.';
