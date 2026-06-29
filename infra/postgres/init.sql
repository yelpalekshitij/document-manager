-- VaultDoc Postgres init: runs once on a fresh data volume.
-- The pgvector extension powers embeddings used by the Ask-Your-Vault feature.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
