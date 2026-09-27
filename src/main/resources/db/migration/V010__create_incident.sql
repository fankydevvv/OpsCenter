-- ============================================================================
-- V010 - Incident core (01-SRS §6, §7, §21; 02-SAD §9; 03-DB §12, §13.2, §13.3,
--        §27, §28, §38.3, §38.4; blueprint D-54..D-61)
--
-- incidents               : the aggregate; status follows the state machine of
--                           02-SAD §9 (enforced in Java, the CHECK lists the states).
-- incident_alerts         : which alerts belong to an incident (TRIGGER = the alert
--                           that opened it, CORRELATED = grouped later, D-54).
-- incident_status_history : one row per status change, append-only (03-DB §13.2).
-- incident_timeline       : human readable story of the incident, append-only
--                           (03-DB §13.3). event_type is an open catalogue, so no CHECK.
--
-- State change + history + timeline + audit + outbox are always written in ONE
-- transaction (03-DB §27, §38.5).
-- ============================================================================

-- D-60: INC-000001, INC-000002 ... from a sequence: monotonic and race free
-- (two transactions never draw the same number), while the primary key stays a UUID.
CREATE SEQUENCE incident_no_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE incidents (
    id                 UUID PRIMARY KEY,
    incident_no        VARCHAR(50)  NOT NULL,
    organization_id    UUID         NOT NULL REFERENCES organizations (id),
    service_id         UUID         NULL REFERENCES services (id),
    owning_team_id     UUID         NULL REFERENCES teams (id),
    assignee_id        UUID         NULL REFERENCES users (id),
    title              VARCHAR(500) NOT NULL,
    description        TEXT         NULL,
    severity           VARCHAR(20)  NOT NULL CHECK (severity IN ('P1','P2','P3','P4')),
    priority           VARCHAR(20)  NULL     CHECK (priority IS NULL OR priority IN ('P1','P2','P3','P4')),
    status             VARCHAR(30)  NOT NULL CHECK (status IN ('OPEN','ASSIGNED','ACKNOWLEDGED','INVESTIGATING',
                                                               'MITIGATED','RESOLVED','VERIFIED','CLOSED','REOPENED')),
    source             VARCHAR(50)  NOT NULL CHECK (source IN ('ALERTMANAGER','WEBHOOK','API','MANUAL')),
    environment        VARCHAR(50)  NULL,
    fingerprint        VARCHAR(128) NULL,
    occurrence_count   BIGINT       NOT NULL DEFAULT 1 CHECK (occurrence_count >= 1),
    acknowledged_at    TIMESTAMPTZ  NULL,
    investigating_at   TIMESTAMPTZ  NULL,
    mitigated_at       TIMESTAMPTZ  NULL,
    resolved_at        TIMESTAMPTZ  NULL,
    verified_at        TIMESTAMPTZ  NULL,
    closed_at          TIMESTAMPTZ  NULL,
    reopened_at        TIMESTAMPTZ  NULL,
    root_cause         TEXT         NULL,
    resolution         TEXT         NULL,
    mitigation_summary TEXT         NULL,
    recovery_summary   TEXT         NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         UUID         NULL,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by         UUID         NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_incidents_no        UNIQUE (incident_no),
    CONSTRAINT ck_incidents_no_format CHECK (incident_no ~ '^INC-[0-9]{6,}$')
);

CREATE INDEX idx_incidents_status_severity ON incidents (status, severity);
CREATE INDEX idx_incidents_service_status  ON incidents (service_id, status);
CREATE INDEX idx_incidents_assignee_status ON incidents (assignee_id, status);
CREATE INDEX idx_incidents_team_status     ON incidents (owning_team_id, status);
CREATE INDEX idx_incidents_created_at      ON incidents (created_at DESC);
CREATE INDEX idx_incidents_fingerprint     ON incidents (fingerprint);
-- At most one non-final incident per correlation key (D-54); final guard behind the Redis lock (D-51).
CREATE UNIQUE INDEX uk_incidents_open_correlation ON incidents (organization_id, fingerprint)
    WHERE fingerprint IS NOT NULL AND status NOT IN ('RESOLVED','VERIFIED','CLOSED');

COMMENT ON COLUMN incidents.fingerprint        IS 'Correlation key sha256(alertName, service, ENV) shared by grouped alerts (FR-ALT-04, D-46).';
COMMENT ON COLUMN incidents.occurrence_count   IS 'Alert notifications counted into this incident; bulk-incremented without touching version (D-57).';
COMMENT ON COLUMN incidents.version            IS 'Optimistic lock for human commands only; counters are bulk-updated without it (D-57).';
COMMENT ON COLUMN incidents.updated_by         IS 'Additive audit column (03-DB §3.4, D-61).';
COMMENT ON COLUMN incidents.mitigation_summary IS 'Text of the mitigate command / the "mitigation" field of resolve (04-API §7.3, D-61).';
COMMENT ON COLUMN incidents.resolved_at        IS 'Cleared when the incident is REOPENED; the history keeps the old value (D-60).';

CREATE TABLE incident_alerts (
    incident_id   UUID        NOT NULL REFERENCES incidents (id),
    alert_id      UUID        NOT NULL REFERENCES alerts (id),
    relation_type VARCHAR(30) NOT NULL CHECK (relation_type IN ('TRIGGER','CORRELATED','DUPLICATE_CONTEXT')),
    is_primary    BOOLEAN     NOT NULL DEFAULT FALSE,
    linked_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (incident_id, alert_id)
);

CREATE INDEX idx_incident_alerts_alert ON incident_alerts (alert_id);
-- Exactly one primary (triggering) alert per incident.
CREATE UNIQUE INDEX uk_incident_alerts_primary ON incident_alerts (incident_id) WHERE is_primary;

CREATE TABLE incident_status_history (
    id          UUID PRIMARY KEY,
    incident_id UUID        NOT NULL REFERENCES incidents (id),
    from_status VARCHAR(30) NULL,
    to_status   VARCHAR(30) NOT NULL,
    changed_by  UUID        NULL REFERENCES users (id),
    changed_at  TIMESTAMPTZ NOT NULL,
    reason      TEXT        NULL
);

CREATE INDEX idx_status_history_incident_time ON incident_status_history (incident_id, changed_at);

COMMENT ON TABLE  incident_status_history            IS 'Append-only (03-DB §13.2, §29).';
COMMENT ON COLUMN incident_status_history.changed_by IS 'NULL = system transition (creation from an alert, REOPENED by a re-fired alert).';

CREATE TABLE incident_timeline (
    id          UUID PRIMARY KEY,
    incident_id UUID         NOT NULL REFERENCES incidents (id),
    event_type  VARCHAR(100) NOT NULL,
    actor_id    UUID         NULL REFERENCES users (id),
    source      VARCHAR(50)  NOT NULL CHECK (source IN ('USER','SYSTEM','ALERTMANAGER','WEBHOOK','API')),
    title       VARCHAR(500) NOT NULL,
    description TEXT         NULL,
    event_at    TIMESTAMPTZ  NOT NULL,
    metadata    JSONB        NULL
);

CREATE INDEX idx_incident_timeline_incident_time ON incident_timeline (incident_id, event_at);

COMMENT ON TABLE  incident_timeline            IS 'Append-only story of an incident (03-DB §13.3, §29).';
COMMENT ON COLUMN incident_timeline.event_type IS 'Open catalogue (TimelineEventType): INCIDENT_CREATED, ALERT_LINKED, STATUS_CHANGED, ALERT_RESOLVED ...';
