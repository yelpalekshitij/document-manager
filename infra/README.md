# Local Dev Infrastructure

Brings up every backing service VaultDoc needs:

| Service | Image | Host port | Purpose |
|---|---|---|---|
| Postgres + pgvector | `pgvector/pgvector:pg16` | `5432` | Metadata, embeddings |
| Redis | `redis:7-alpine` | `6379` | Sessions, queues, cache |
| Elasticsearch | `8.15.3` | `9200` | Full-text + multilingual search |
| MinIO | latest stable | `9000` (S3), `9001` (UI) | S3-compatible object storage |
| Mailhog | `1.0.1` | `1025` (SMTP), `8025` (UI) | Catch-all SMTP for alert testing |

## First-time setup

```bash
cp .env.example .env
docker compose up -d
docker compose ps
```

All services should report `(healthy)` within ~30 seconds. The `minio-init` container is intentionally short-lived — it creates the `vaultdoc-docs` bucket and exits.

## Where things live

- **Postgres data**: named volume `vaultdoc_postgres-data`
- **Redis data**: named volume `vaultdoc_redis-data`
- **Elasticsearch data**: named volume `vaultdoc_es-data`
- **MinIO data**: named volume `vaultdoc_minio-data`

To wipe local state and start fresh:

```bash
docker compose down -v
```

## Web UIs

- MinIO console → http://localhost:9001 (login with `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`)
- Mailhog inbox → http://localhost:8025
- Elasticsearch → http://localhost:9200/_cluster/health

## Connection strings (for the backend's `.env` or `application-local.yml`)

```
DATABASE_URL=jdbc:postgresql://localhost:5432/vaultdoc
REDIS_URL=redis://:vaultdoc_dev_pw@localhost:6379
ELASTICSEARCH_URL=http://localhost:9200
S3_ENDPOINT=http://localhost:9000
S3_BUCKET=vaultdoc-docs
SMTP_HOST=localhost
SMTP_PORT=1025
```

## Notes

- Elasticsearch security is disabled for dev. **Do not** copy this config to prod.
- The disk-watermark allocator is disabled — prevents indices from going read-only on a near-full laptop disk during development.
- The pgvector extension is created automatically by `postgres/init.sql` (runs once on a fresh data volume).

## Browser-direct uploads to MinIO

Phase 1.2 uses presigned PUT URLs: the browser PUTs documents straight to MinIO at
`http://localhost:9000`. MinIO's default CORS allows cross-origin presigned-URL
uploads from any origin in dev. If your browser blocks the preflight OPTIONS, run

```bash
docker exec vaultdoc-minio-init \
    mc admin config set local/ cors:default \
    allow_origin='*' \
    allow_methods='GET,PUT,HEAD,OPTIONS' \
    allow_headers='Authorization,Content-Type,X-Amz-*' \
&& docker exec vaultdoc-minio-init mc admin service restart local/
```

(For prod, scope `allow_origin` to your web origin and tighten headers.)
