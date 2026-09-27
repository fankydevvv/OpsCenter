-- ============================================================================
-- V011 - Permissions of the Alert and Incident endpoints (04-API §6, §7;
--        01-SRS §2; blueprint §6)
--
-- service.read/create/update and system.read (ids …1017-…1019, …1028) were already
-- seeded by V007.1 together with the endpoints that enforce them, so this file
-- only adds the alert/incident codes (ids …1020-…1027). Same rule as V005: a
-- permission exists only once an endpoint checks it (no incident.assign/verify
-- yet - Sprint 3/4).
--
-- Matrix (blueprint §6):
--   alert.read            ADMIN, COORDINATOR, ENGINEER
--   alert.raw.read        ADMIN            (raw webhook may hold sensitive labels, R-28)
--   incident.read         ADMIN, COORDINATOR, ENGINEER   (+ /operations/summary)
--   incident.acknowledge  ADMIN, COORDINATOR, ENGINEER
--   incident.investigate  ADMIN, ENGINEER
--   incident.mitigate     ADMIN, ENGINEER
--   incident.resolve      ADMIN, ENGINEER
--   incident.close        ADMIN, COORDINATOR, ENGINEER   (guarded 422 in Sprint 2)
-- New permissions reach a token at the next login/refresh (D-07, R-29).
-- ============================================================================

INSERT INTO permissions (id, code, resource, action) VALUES
    ('00000000-0000-4000-8000-000000001020', 'alert.read',           'alert',    'read'),
    ('00000000-0000-4000-8000-000000001021', 'alert.raw.read',       'alert',    'raw.read'),
    ('00000000-0000-4000-8000-000000001022', 'incident.read',        'incident', 'read'),
    ('00000000-0000-4000-8000-000000001023', 'incident.acknowledge', 'incident', 'acknowledge'),
    ('00000000-0000-4000-8000-000000001024', 'incident.investigate', 'incident', 'investigate'),
    ('00000000-0000-4000-8000-000000001025', 'incident.mitigate',    'incident', 'mitigate'),
    ('00000000-0000-4000-8000-000000001026', 'incident.resolve',     'incident', 'resolve'),
    ('00000000-0000-4000-8000-000000001027', 'incident.close',       'incident', 'close');

-- ADMIN: every code of this file (listed by code, not by id range, so the V007.1 rows are
-- never inserted twice).
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('alert.read', 'alert.raw.read', 'incident.read', 'incident.acknowledge',
                                 'incident.investigate', 'incident.mitigate', 'incident.resolve', 'incident.close')
WHERE r.code = 'ADMIN';

-- COORDINATOR: watches and acknowledges, does not work the technical investigation.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('alert.read', 'incident.read', 'incident.acknowledge', 'incident.close')
WHERE r.code = 'COORDINATOR';

-- ENGINEER: works the incident end to end.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('alert.read', 'incident.read', 'incident.acknowledge', 'incident.investigate',
                                 'incident.mitigate', 'incident.resolve', 'incident.close')
WHERE r.code = 'ENGINEER';
