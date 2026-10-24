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

## OCR (Tesseract)

Phase 1.4 OCRs images (JPEG/PNG/TIFF/WebP) and scanned PDF pages with the
`tesseract` CLI, which the backend runs as a child process. It is not part of
this compose stack — it runs wherever the API runs.

- **Local `bootRun`:** install it on the host.
  - macOS: `brew install tesseract tesseract-lang` (`tesseract-lang` adds `hin` and the other packs)
  - Debian/Ubuntu: `sudo apt-get install tesseract-ocr tesseract-ocr-eng tesseract-ocr-hin`
- **Container:** `backend/api-gateway/Dockerfile` installs `tesseract-ocr` + `eng` + `hin`.
- Check what is installed with `tesseract --list-langs`. The backend logs the languages it will
  use at startup; a configured pack that is missing (e.g. `hin` with a bare `brew install tesseract`)
  is logged as a WARN and OCR continues with the installed subset.
- No tesseract at all: text PDFs still work; images and scanned PDFs end `ocrStatus=FAILED`
  with the reason shown in the viewer.

| Env var | Default | Meaning |
|---|---|---|
| `OCR_LANGUAGES` | `eng+hin` | Tesseract languages, `+`-separated |
| `TESSERACT_PATH` | `tesseract` | Binary name on `PATH` or absolute path (e.g. `/opt/homebrew/bin/tesseract`) |
| `TESSDATA_DIR` | *(tesseract default)* | Passed as `--tessdata-dir` (e.g. for `tessdata_best`) |
| `OCR_TESSERACT_TIMEOUT` | `60s` | Per image / per PDF page; the process is killed on timeout → `FAILED` |
| `OCR_MAX_IMAGE_BYTES` | `26214400` | Larger images → `FAILED` before decoding |
| `OCR_MAX_IMAGE_PIXELS` | `50000000` | Pixel cap from the image header (and for rendered PDF pages) → `FAILED`, no OOM |
| `OCR_PDF_FALLBACK_ENABLED` | `true` | OCR PDF pages whose text layer is (near-)empty |
| `OCR_PDF_MAX_PAGES` | `20` | Max scanned pages OCR'd per PDF |
| `OCR_PDF_DPI` | `300` | Render resolution for scanned pages (72–600) |
| `OCR_PDF_MIN_TEXT_CHARS` | `25` | A page with fewer visible chars is treated as scanned |
| `OCR_TEMP_DIR` | *(java.io.tmpdir)* | Parent for per-job scratch dirs (owner-only, deleted after every job) |
