-- ============================================================================
-- V002 - Organization & Team (03-DB §6, §39.2 where applicable)
--
-- Master data uses soft delete (is_active + deleted_at, 03-DB §3.3 / §29) and
-- carries the audit columns + version like every business aggregate.
-- teams.department_id (03-DB §39.2) is NOT created here because the
-- departments table belongs to a later sprint; that migration will ALTER teams.
-- ============================================================================

CREATE TABLE organizations (
    id         UUID PRIMARY KEY,
    code       VARCHAR(100) NOT NULL,
    name       VARCHAR(255) NOT NULL,
    status     VARCHAR(30)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID NULL,
    version    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_organizations_code UNIQUE (code)
);
COMMENT ON TABLE organizations IS 'Present from day one so a multi-tenant upgrade only adds filters (03-DB §6.1). The base seeds a single DEFAULT organization.';

CREATE TABLE teams (
    id              UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id),
    code            VARCHAR(100) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT NULL,
    team_type       VARCHAR(30) NULL,
    on_call_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    status          VARCHAR(30) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at      TIMESTAMPTZ NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      UUID NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID NULL,
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_teams_org_code UNIQUE (organization_id, code)
);
CREATE INDEX idx_teams_org_status ON teams (organization_id, status);
COMMENT ON COLUMN teams.team_type       IS 'DEV / QA / DEVOPS / DBA ... - an operational grouping, NOT an RBAC role (03-DB §39.2).';
COMMENT ON COLUMN teams.on_call_enabled IS 'Whether the team participates in on-call routing (03-DB §39.2).';
COMMENT ON COLUMN teams.status          IS 'INACTIVE is the soft delete of a team (03-DB §29).';

CREATE TABLE team_members (
    team_id     UUID NOT NULL REFERENCES teams (id),
    user_id     UUID NOT NULL REFERENCES users (id),
    member_type VARCHAR(20) NOT NULL DEFAULT 'PRIMARY' CHECK (member_type IN ('PRIMARY', 'SECONDARY', 'ON_CALL')),
    team_role   VARCHAR(50) NULL,
    is_primary  BOOLEAN NOT NULL DEFAULT FALSE,
    valid_from  TIMESTAMPTZ NULL,
    valid_to    TIMESTAMPTZ NULL,
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (team_id, user_id)
);
CREATE INDEX idx_team_members_user ON team_members (user_id);
COMMENT ON COLUMN team_members.member_type IS 'PRIMARY | SECONDARY | ON_CALL (03-DB §39.2); used by incident routing in a later sprint.';
