-- ============================================================================
-- V011.1 - DEMO / REFERENCE DATA (no secrets) - blueprint D-64, 05-DEPLOY
--          §23.4-§23.5, 07-TC §31.
--
-- Lives in classpath:db/seed-demo, a Flyway location enabled by the profiles
-- "local" and "test" and by the container profile "dev" through
-- OPSCENTER_FLYWAY_LOCATIONS (docker-compose.yml). It is NOT in db/seed-dev on
-- purpose: the one-command container path must know the service odoo-erp (so
-- the demo alert of opscenter-demo-target is MAPPED) but must never load the
-- DEV accounts with published passwords (D-27).
--
-- odoo-erp          : the monitored reference system of 01-SRS §23; in the local
--                     demo its role is played by the container
--                     opscenter-demo-target (prom/node-exporter) that Prometheus
--                     scrapes with labels service=odoo-erp, environment=DEV.
-- opscenter-backend : OpsCenter monitors itself.
-- Owner team = PLATFORM (seeded by V005). Fixed ids …0401-0402 / …0411-0412.
-- ============================================================================

INSERT INTO services (id, organization_id, code, name, description, status, owning_team_id, metadata)
VALUES
    ('00000000-0000-4000-8000-000000000401', '00000000-0000-4000-8000-000000000001', 'odoo-erp',
     'Odoo ERP (reference monitored system)',
     'External monitored system of 01-SRS §23; in the local demo its role is played by the container opscenter-demo-target.',
     'ACTIVE', '00000000-0000-4000-8000-000000000102',
     '{"reference":"05-DEPLOY §23.4","demoTarget":"opscenter-demo-target"}'),
    ('00000000-0000-4000-8000-000000000402', '00000000-0000-4000-8000-000000000001', 'opscenter-backend',
     'OpsCenter backend', 'OpsCenter monitors itself (deploy/prometheus/rules/opscenter.rules.yml).',
     'ACTIVE', '00000000-0000-4000-8000-000000000102', NULL);

INSERT INTO service_environments (id, service_id, environment_code, status, health_endpoint, metric_endpoint, dashboard_url)
VALUES
    ('00000000-0000-4000-8000-000000000411', '00000000-0000-4000-8000-000000000401', 'DEV', 'ACTIVE',
     NULL, 'http://demo-target:9100/metrics', 'http://localhost:3100'),
    ('00000000-0000-4000-8000-000000000412', '00000000-0000-4000-8000-000000000402', 'DEV', 'ACTIVE',
     'http://backend:8746/actuator/health', 'http://backend:8746/actuator/prometheus', 'http://localhost:3100');
