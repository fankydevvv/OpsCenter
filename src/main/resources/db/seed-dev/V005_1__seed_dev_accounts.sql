-- ============================================================================
-- V005.1 - DEV / TEST accounts (07-TC §5: Admin A, Coordinator A, Engineer A/B)
--
-- !!! DEV / TEST ONLY - NEVER APPLIED IN DEMO/PROD !!!
-- This file lives in classpath:db/seed-dev, a Flyway location that only
-- application-local.yml and application-test.yml add (D-27). The container
-- profile "dev" does not see it, so a demo or production database never holds
-- an account with a published password (05-DEPLOY §9, §19).
--
--     admin        / Admin@123          (role ADMIN)
--     coordinator  / Coordinator@123    (role COORDINATOR, Team Payment secondary)
--     engineer.a   / Engineer@123       (role ENGINEER, Team Payment primary)
--     engineer.b   / Engineer@123       (role ENGINEER, Team Platform primary)
--
-- The hashes are in the DelegatingPasswordEncoder format {bcrypt}$2a$10$...
-- (D-04) and were verified against the passwords above with
-- spring-security-crypto 7.1.1. Fixed UUIDs so tests can reference the rows.
--
-- Note: a database migrated with this location and later started WITHOUT it
-- fails Flyway validation ("applied migration not resolved locally: 5.1") -
-- that is intended, it stops a dev database from being promoted by accident.
-- ============================================================================

INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES
    ('00000000-0000-4000-8000-000000000021', 'admin',       'admin@opscenter.local',
     '{bcrypt}$2a$10$Cy91Wov6y7849oLT6F2tY.R.vLVohvg3xsDV8CLzhwGlGGTsFfhVG', 'Admin A',       'ACTIVE'),
    ('00000000-0000-4000-8000-000000000022', 'coordinator', 'coordinator@opscenter.local',
     '{bcrypt}$2a$10$pl01cBJ.YTM20uTetDCiQuP/9GV0nyooTG6OtaPFYislhBHIotSp2', 'Coordinator A', 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000023', 'engineer.a',  'engineer.a@opscenter.local',
     '{bcrypt}$2a$10$wfPIQymIuTi5/Bo4BaUD5u.xeI.B7.YE3hJrdgFIM4pWzr8GwBKf.', 'Engineer A',    'ACTIVE'),
    ('00000000-0000-4000-8000-000000000024', 'engineer.b',  'engineer.b@opscenter.local',
     '{bcrypt}$2a$10$wfPIQymIuTi5/Bo4BaUD5u.xeI.B7.YE3hJrdgFIM4pWzr8GwBKf.', 'Engineer B',    'ACTIVE');

INSERT INTO user_roles (user_id, role_id) VALUES
    ('00000000-0000-4000-8000-000000000021', '00000000-0000-4000-8000-000000000011'),
    ('00000000-0000-4000-8000-000000000022', '00000000-0000-4000-8000-000000000012'),
    ('00000000-0000-4000-8000-000000000023', '00000000-0000-4000-8000-000000000013'),
    ('00000000-0000-4000-8000-000000000024', '00000000-0000-4000-8000-000000000013');

-- Team memberships (blueprint §6.4): engineer.a -> PAYMENT (primary),
-- engineer.b -> PLATFORM (primary), coordinator -> PAYMENT (secondary).
INSERT INTO team_members (team_id, user_id, member_type, is_primary) VALUES
    ('00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000023', 'PRIMARY',   TRUE),
    ('00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000024', 'PRIMARY',   TRUE),
    ('00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000022', 'SECONDARY', FALSE);
