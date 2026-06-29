-- Phase 1.1a: real auth schema.
-- Extends the placeholder users table from V1 and adds refresh_tokens.

ALTER TABLE users
    ADD COLUMN password_hash  TEXT,
    ADD COLUMN status         VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN last_login_at  TIMESTAMPTZ;

ALTER TABLE users
    ADD CONSTRAINT users_status_check
        CHECK (status IN ('ACTIVE', 'LOCKED', 'PENDING_VERIFICATION'));

COMMENT ON COLUMN users.password_hash IS 'Argon2id encoded hash. NULL means no password set (passkey-only account in 1.1b).';
COMMENT ON COLUMN users.status IS 'Account lifecycle state.';
COMMENT ON COLUMN users.last_login_at IS 'Wall-clock of the most recent successful login.';

CREATE TABLE refresh_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Session family: refresh-token rotation issues a new row in the same family.
    -- If a revoked token is ever presented again, we revoke the whole family.
    session_id  UUID         NOT NULL,
    token_hash  TEXT         NOT NULL UNIQUE,
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ,
    user_agent  TEXT,
    ip_address  TEXT
);

CREATE INDEX refresh_tokens_user_idx    ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_session_idx ON refresh_tokens (session_id);
CREATE INDEX refresh_tokens_expires_idx ON refresh_tokens (expires_at) WHERE revoked_at IS NULL;

COMMENT ON TABLE refresh_tokens IS 'Active and recently-revoked refresh tokens. Rotation: every /auth/refresh issues a new row in the same session_id and marks the previous one revoked. Presenting a revoked token revokes the whole session_id (reuse detection).';
