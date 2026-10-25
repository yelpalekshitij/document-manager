-- Phase 1.4: durable retry list for tag → search-index syncs that failed after
-- the tag change committed (Elasticsearch down, bulk rejected). The gateway's
-- TagIndexSynchronizer enqueues on failure and a periodic sweep re-pushes the
-- document's current tags until ES accepts them.
--
-- Rollback: DROP TABLE tag_index_resync;  (no other object depends on it; the
-- only loss is pending retries, which the next edit of each document repairs)

CREATE TABLE tag_index_resync (
    document_id     UUID         PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
    user_id         UUID         NOT NULL,
    generation      BIGINT       NOT NULL DEFAULT 1,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    enqueued_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX tag_index_resync_due_idx ON tag_index_resync (next_attempt_at);

COMMENT ON TABLE tag_index_resync IS 'Documents whose tags must be re-pushed to the search index; drained by TagIndexSynchronizer.retryPending.';
COMMENT ON COLUMN tag_index_resync.generation IS 'Bumped on every re-enqueue so a sweep only deletes the version it synced.';
