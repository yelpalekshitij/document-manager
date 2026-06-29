-- Phase 1.1e: TOTP 2FA + backup codes.

CREATE TABLE totp_secrets (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    -- AES-256-GCM encrypted base32 TOTP secret. Format: base64(iv)||base64(ciphertext)
    secret_enc   TEXT         NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE totp_secrets IS '2FA TOTP secret per user, encrypted at rest. One row maximum per user; deleted (not soft-disabled) when 2FA is turned off.';

CREATE TABLE backup_codes (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash   TEXT         NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX backup_codes_user_idx ON backup_codes(user_id);

COMMENT ON TABLE backup_codes IS 'Single-use backup codes for 2FA recovery. Stored as Argon2id hashes. 10 codes are generated when 2FA is enabled; regenerating replaces all rows.';
