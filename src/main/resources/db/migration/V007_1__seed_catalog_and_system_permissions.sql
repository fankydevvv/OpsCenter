-- ============================================================================
-- V007.1 - Permissions of the endpoints shipped together with the Service
--          Catalog (blueprint §6): service.read / service.create / service.update
--          (04-API §5) and system.read (GET /api/v1/system/status, D-63).
--
-- Same rule as V005: a permission exists only once the endpoint that enforces it
-- exists. The fixed ids are the ones of blueprint §5.5, so documentation and
-- tests can reference them. The alert/incident permissions (…1020 - …1027) are
-- seeded by the Sprint 2 migration of those modules, not here.
--
-- Matrix (blueprint §6):
--   service.read   ADMIN, COORDINATOR, ENGINEER
--   service.create ADMIN
--   service.update ADMIN
--   system.read    ADMIN
-- New permissions reach a token at the next login/refresh (D-07).
-- ============================================================================

INSERT INTO permissions (id, code, resource, action) VALUES
    ('00000000-0000-4000-8000-000000001017', 'service.read',   'service', 'read'),
    ('00000000-0000-4000-8000-000000001018', 'service.create', 'service', 'create'),
    ('00000000-0000-4000-8000-000000001019', 'service.update', 'service', 'update'),
    ('00000000-0000-4000-8000-000000001028', 'system.read',    'system',  'read');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('service.read', 'service.create', 'service.update', 'system.read')
WHERE r.code = 'ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'service.read'
WHERE r.code IN ('COORDINATOR', 'ENGINEER');
