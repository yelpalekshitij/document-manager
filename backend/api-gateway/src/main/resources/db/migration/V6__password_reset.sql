-- Phase 1.1f: password-reset tokens.
-- 1-hour TTL, single-use, SHA-256-hashed at rest.

CREATE TABLE password_reset_tokens (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  TEXT         NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX password_reset_user_idx ON password_reset_tokens(user_id);

COMMENT ON TABLE password_reset_tokens IS 'Single-use password-reset tokens, valid for 1 hour. Hashed at rest. Re-issuing for the same user revokes any prior unconsumed token.';
