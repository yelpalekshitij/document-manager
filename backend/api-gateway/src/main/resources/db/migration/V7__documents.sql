-- Phase 1.2: document vault foundation.
-- Folders (adjacency list), documents, tags, and the document↔tag join.

CREATE TABLE folders (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    parent_id   UUID         REFERENCES folders(id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,
    color       VARCHAR(16),
    icon        VARCHAR(64),
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    is_system   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Postgres treats NULLs as distinct in compound UNIQUE — we need two
-- partial indexes to forbid duplicate names within a (user, parent) pair.
CREATE UNIQUE INDEX folders_unique_top_level
    ON folders (user_id, LOWER(name))
    WHERE parent_id IS NULL;
CREATE UNIQUE INDEX folders_unique_in_parent
    ON folders (user_id, parent_id, LOWER(name))
    WHERE parent_id IS NOT NULL;

CREATE INDEX folders_user_idx   ON folders (user_id);
CREATE INDEX folders_parent_idx ON folders (parent_id);

COMMENT ON TABLE folders IS 'Adjacency-list folder tree. is_system marks the 8 default folders seeded on first visit.';

CREATE TABLE documents (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    folder_id         UUID         REFERENCES folders(id) ON DELETE SET NULL,
    original_filename VARCHAR(500) NOT NULL,
    mime_type         VARCHAR(120) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    storage_key       VARCHAR(1000) NOT NULL UNIQUE,
    sha256            VARCHAR(64)  NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    -- Phase 2 fills these in (extraction).
    document_type     VARCHAR(64),
    issued_at         DATE,
    expires_at        DATE,
    uploaded_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finalized_at      TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT documents_status_check
        CHECK (status IN ('PENDING','READY','FAILED','DELETED'))
);

CREATE INDEX documents_user_idx       ON documents (user_id);
CREATE INDEX documents_folder_idx     ON documents (folder_id);
CREATE INDEX documents_status_idx     ON documents (status);
CREATE INDEX documents_uploaded_idx   ON documents (user_id, uploaded_at DESC);
CREATE INDEX documents_user_status_uploaded_idx
    ON documents (user_id, status, uploaded_at DESC);

COMMENT ON TABLE documents IS 'One row per uploaded blob. PENDING → READY transition happens at finalize-upload.';
COMMENT ON COLUMN documents.storage_key IS 'S3/MinIO key. Shape: users/{userId}/docs/{docId}/{slug(filename)}.';

CREATE TABLE tags (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(64)  NOT NULL,
    color       VARCHAR(16),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX tags_unique_name ON tags (user_id, LOWER(name));
CREATE INDEX tags_user_idx ON tags (user_id);

CREATE TABLE document_tags (
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    tag_id      UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, tag_id)
);

CREATE INDEX document_tags_tag_idx ON document_tags (tag_id);
