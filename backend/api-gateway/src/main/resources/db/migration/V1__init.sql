-- Phase 1.0 baseline. Real auth schema lands in Phase 1.1.
-- This migration only proves Flyway is wired and the DB is reachable.

CREATE TABLE IF NOT EXISTS users (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email        VARCHAR(320) NOT NULL UNIQUE,
    display_name VARCHAR(200),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS users_email_lower_idx ON users (LOWER(email));

COMMENT ON TABLE users IS 'Minimal users table — Phase 1.0 connectivity smoke test.';
