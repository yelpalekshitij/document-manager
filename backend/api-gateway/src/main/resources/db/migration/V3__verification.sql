-- Phase 1.1b: email verification tokens.
-- Single-use, hashed-at-rest tokens issued on register and resend.

CREATE TABLE verification_tokens (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   TEXT         NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX verification_tokens_user_idx ON verification_tokens (user_id);
CREATE INDEX verification_tokens_open_idx ON verification_tokens (expires_at) WHERE consumed_at IS NULL;

COMMENT ON TABLE verification_tokens IS 'Single-use email-verification tokens. Hashed at rest. Reissuing for the same user revokes any older row.';
