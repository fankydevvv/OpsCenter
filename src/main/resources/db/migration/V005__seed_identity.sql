-- ============================================================================
-- V005 - Seed reference data: organization, teams, permission catalogue and
--        the three baseline roles (01-SRS §2, 04-API §4, 07-TC §5, blueprint §6).
--
-- This migration runs in EVERY environment and therefore contains no user
-- account. The four DEV/TEST accounts with well-known passwords
-- (admin/Admin@123 ...) live in db/seed-dev/V005_1__seed_dev_accounts.sql,
-- a Flyway location that only the "local" and "test" profiles enable (D-27).
-- A DEMO/PROD database gets its first administrator from
-- OPSCENTER_BOOTSTRAP_ADMIN_PASSWORD at start-up instead (05-DEPLOY §9, §19).
--
-- Fixed UUIDs: tests and documentation can reference rows by id.
-- created_by / updated_by are left NULL = "system" (D-11).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Organization and teams (07-TC §5: "Team Payment", "Team Platform")
-- ---------------------------------------------------------------------------
INSERT INTO organizations (id, code, name, status, is_active) VALUES
    ('00000000-0000-4000-8000-000000000001', 'DEFAULT', 'OpsCenter Default Organization', 'ACTIVE', TRUE);

INSERT INTO teams (id, organization_id, code, name, description, team_type, on_call_enabled, status, is_active) VALUES
    ('00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000001', 'PAYMENT',
     'Team Payment', 'Owns payment-service and its runbooks (07-TC §5)', 'DEV', TRUE, 'ACTIVE', TRUE),
    ('00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000001', 'PLATFORM',
     'Team Platform', 'Owns api-gateway and shared infrastructure', 'DEVOPS', TRUE, 'ACTIVE', TRUE);

-- ---------------------------------------------------------------------------
-- Permission catalogue: 15 codes of the base (blueprint §6.2); V006 adds
-- user.delete (D-26). Later sprints add incident.*, service.*, alert.* with
-- their own seed migrations so that no permission exists before the endpoint
-- that enforces it.
-- ---------------------------------------------------------------------------
INSERT INTO permissions (id, code, resource, action) VALUES
    ('00000000-0000-4000-8000-000000001001', 'user.read',              'user',         'read'),
    ('00000000-0000-4000-8000-000000001002', 'user.create',            'user',         'create'),
    ('00000000-0000-4000-8000-000000001003', 'user.update',            'user',         'update'),
    ('00000000-0000-4000-8000-000000001004', 'user.lock',              'user',         'lock'),
    ('00000000-0000-4000-8000-000000001005', 'user.role.assign',       'user',         'role.assign'),
    ('00000000-0000-4000-8000-000000001006', 'role.read',              'role',         'read'),
    ('00000000-0000-4000-8000-000000001007', 'role.create',            'role',         'create'),
    ('00000000-0000-4000-8000-000000001008', 'role.permission.update', 'role',         'permission.update'),
    ('00000000-0000-4000-8000-000000001009', 'permission.read',        'permission',   'read'),
    ('00000000-0000-4000-8000-000000001010', 'organization.read',      'organization', 'read'),
    ('00000000-0000-4000-8000-000000001011', 'organization.update',    'organization', 'update'),
    ('00000000-0000-4000-8000-000000001012', 'team.read',              'team',         'read'),
    ('00000000-0000-4000-8000-000000001013', 'team.create',            'team',         'create'),
    ('00000000-0000-4000-8000-000000001014', 'team.update',            'team',         'update'),
    ('00000000-0000-4000-8000-000000001015', 'team.member.manage',     'team',         'member.manage');

-- ---------------------------------------------------------------------------
-- Roles (01-SRS §2 actors). Roles are data: an administrator may add more.
-- ---------------------------------------------------------------------------
INSERT INTO roles (id, code, name, description) VALUES
    ('00000000-0000-4000-8000-000000000011', 'ADMIN',       'Administrator',
     'Manages users, roles, permissions, teams and integrations (01-SRS §2.1)'),
    ('00000000-0000-4000-8000-000000000012', 'COORDINATOR', 'Coordinator',
     'Coordinates incidents and teams; reads identity data (01-SRS §2.2)'),
    ('00000000-0000-4000-8000-000000000013', 'ENGINEER',    'Engineer',
     'Works incidents assigned to their team (01-SRS §2.3)');

-- ADMIN: every permission of the base.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN';

-- COORDINATOR: read identity data, manage teams and their members.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('user.read', 'role.read', 'permission.read', 'organization.read',
                                 'team.read', 'team.create', 'team.update', 'team.member.manage')
WHERE r.code = 'COORDINATOR';

-- ENGINEER: see the organization and teams only.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('organization.read', 'team.read')
WHERE r.code = 'ENGINEER';
