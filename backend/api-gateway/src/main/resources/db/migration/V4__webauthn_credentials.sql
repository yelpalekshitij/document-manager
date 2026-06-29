-- Phase 1.1c: WebAuthn / passkey credentials.
-- One row per registered authenticator. A user can register many passkeys
-- (laptop fingerprint, phone, security key) and label each.

CREATE TABLE webauthn_credentials (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- The raw credential ID returned by the authenticator. Unique globally.
    credential_id   BYTEA        NOT NULL UNIQUE,
    -- Full CBOR-encoded AttestedCredentialData (AAGUID + credentialId + COSE public key).
    -- We persist it as a single blob to avoid re-serializing on every assertion.
    attestation_data BYTEA       NOT NULL,
    sign_count      BIGINT       NOT NULL DEFAULT 0,
    -- Authenticator vendor (e.g. Apple TouchID has a specific aaguid).
    aaguid          UUID,
    -- Transports the authenticator supports: internal, usb, nfc, ble, hybrid.
    transports      TEXT[]       NOT NULL DEFAULT '{}',
    attestation_fmt TEXT,
    -- Backup eligibility flags from the WebAuthn authenticatorData.
    backup_eligible BOOLEAN      NOT NULL DEFAULT FALSE,
    backup_state    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- The WebAuthn user.id field — we always set this to the user's UUID bytes,
    -- letting us locate a user from a discoverable-credential assertion.
    user_handle     BYTEA        NOT NULL,
    -- User-facing name. Pre-populated on register (e.g. "MacBook Touch ID")
    -- and editable later.
    label           VARCHAR(200) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_used_at    TIMESTAMPTZ
);

CREATE INDEX webauthn_credentials_user_idx        ON webauthn_credentials (user_id);
CREATE INDEX webauthn_credentials_user_handle_idx ON webauthn_credentials (user_handle);

COMMENT ON TABLE webauthn_credentials IS 'Registered WebAuthn / passkey credentials. Sign-counter is stored but not strictly enforced (Apple authenticators do not increment).';
