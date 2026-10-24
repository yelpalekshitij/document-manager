# VaultDoc

> Privacy-first document intelligence platform. Self-hostable. Mobile-first. Built for people who actually need to find that 2021 bank statement at 11 PM.

**Status:** 🚧 Phase 1.4 — Phase 1 (Vault Foundation) is mostly done but **not complete**. The backend is done and green (379 tests): auth with passkeys and TOTP, presigned upload, S3 storage, folders, tags, OCR (PDFBox plus Tesseract for images and scans), and search. The Angular shell has upload, search, viewer and tags; the Phase 1.4 web specs have not been run yet. Still open for Phase 1: the Flutter skeleton has login but no document list. See [BUILD_LOG.md](./BUILD_LOG.md).

A polished `README.md` will be produced at the end of Phase 6. Until then, see:

- **[CLAUDE.md](./CLAUDE.md)** — full project brief, architecture, and roadmap.
- **[infra/README.md](./infra/README.md)** — local dev environment setup.
- **[openapi/vaultdoc.yaml](./openapi/vaultdoc.yaml)** — API contract (single source of truth).

## Repository Layout

```
docman/
├── CLAUDE.md              # Project brief (the spec)
├── openapi/               # OpenAPI 3.1 spec — drives both clients
├── backend/               # Spring Boot 3.x, Java 21, Gradle multi-module
├── web/                   # Angular 18 (standalone components, signals)
├── mobile/                # Flutter, Android-first (iOS in Phase 7)
├── infra/                 # docker-compose for local dev stack
└── .github/workflows/     # CI (backend + web + mobile)
```

## Quick Start (Phase 1.0)

```bash
# 1. Bring up Postgres, Redis, Elasticsearch, MinIO, Mailhog
cd infra && cp .env.example .env && docker compose up -d

# 2. Run the backend
cd ../backend && ./gradlew :api-gateway:bootRun

# 3. Health check
curl -s localhost:8080/api/v1/health | jq
```

Web and mobile dev loops are documented in their respective folders.
