# VaultDoc — Privacy-First Document Intelligence Platform

> This file is the source of truth for the project. Read it fully before writing code. When in doubt, follow this brief over assumptions from the existing scaffold.

## Project Mission

Build **VaultDoc**, a self-hostable (and SaaS-deployable) document vault that goes beyond "store and search." It should function as a **document intelligence platform** — understanding what each document means, when it matters, who it relates to, and what action it requires from the user.

The target user is anyone who has ever frantically searched Gmail for "rental agreement 2022" at 11 PM. The pitch is: *your documents, encrypted, intelligent, and one search away.*

Users will interact through both a **web app** and a **native mobile app**. The mobile app is not an afterthought — for most users it will be the primary interface (camera scanning, push alerts, on-the-go retrieval).

---

## Tech Stack (Hard Requirements)

| Layer | Choice | Reason |
|---|---|---|
| Backend | Spring Boot 4.x (Java 25, virtual threads) | Maturity, ecosystem, team strength |
| Web Frontend | Angular 18+ (standalone components, signals) | Type safety, team strength |
| Mobile | **Flutter 3.x (Dart)** — Android first, iOS later | Single codebase, best document scanner integration, native performance |
| Primary DB | PostgreSQL 17 (with `pgvector` extension) | Metadata + vector embeddings |
| Search | Elasticsearch 9.x | Full-text + multilingual analyzers |
| Cache/Queue | Redis 8 (Streams for job queue) | Sessions, rate limits, processing pipeline |
| Object Storage | S3-compatible (MinIO local, Backblaze B2 prod) | Cheap, portable |
| OCR | Tesseract 5 (CLI) + PDFBox 3 (digital PDFs); fallback to AWS Textract / Google Doc AI for complex layouts | Layered fallback |
| LLM Extraction | Local: Ollama (Llama 3.1 / Qwen). Cloud: Claude Haiku 4.5. Pluggable provider interface. | Privacy-first default, cloud opt-in |
| Auth | Spring Security + JWT + WebAuthn/passkeys + TOTP 2FA | Modern, phishing-resistant |
| Crypto | Per-user envelope encryption: user-derived KEK wraps per-document DEK (AES-256-GCM) | Zero-knowledge mode |
| Observability | OpenTelemetry → Grafana Tempo/Loki/Mimir | Industry standard |
| Container | Docker + Docker Compose for self-host; Helm chart for Kubernetes | Both audiences |

---

## Core Features (Table Stakes)

1. **Multi-format ingest**: PDF, JPG/PNG/HEIC, WebP, TIFF, DOCX, plus phone camera capture with edge detection and perspective correction.
2. **OCR pipeline**: Tesseract for raster, PDFBox for digital PDFs, hybrid extraction for mixed-content PDFs.
3. **LLM-driven structured extraction**: per-document-type schemas (Passport, BankStatement, InsurancePolicy, RentalAgreement, etc.) — each schema produces typed JSON: `issuer`, `issue_date`, `expiry_date`, `policy_number`, `coverage_amount`, `key_parties`, etc.
4. **Full-text search** across OCR'd text with highlighting, filters (type, date range, folder, tags, expiry status).
5. **Folder + tag hierarchy**: opinionated default tree (Identity, Financial, Health, Property, Vehicle, Education, Employment, Travel) with user overrides.
6. **Expiry alerts**: email + push (web push + FCM), configurable lead times (90/30/7/1 day before).
7. **Time-limited shareable links**: expiry, view count cap, password gate, optional watermark with recipient name.

---

## Unique Differentiators (The Reason This Project Stands Out)

These are features that **Paperless-ngx, Google Drive, Evernote, and Notion do NOT have**. Ship at least 8 of these.

### 1. Zero-Knowledge Encryption Mode (flagship privacy feature)
- Optional per-user mode where DEKs are wrapped by a key derived from the user's master password via Argon2id — server stores only ciphertext.
- Server cannot read documents, run OCR server-side, or answer search queries on encrypted blobs.
- Workaround: OCR + extraction runs **client-side in WASM (web) / on-device (mobile)**, or sent through a user-trusted local Ollama instance. Encrypted search via blind indexing.
- This is the privacy moat.

### 2. Document Relationship Graph
- Auto-detect and let users confirm relationships: `Passport → Visa → OCI Card`, `Car Purchase Invoice → RC Book → Insurance Policy → Claim Documents`.
- Render an interactive graph (Cytoscape.js on web, `graphview` package on Flutter).
- When you open one document, see the chain it belongs to.

### 3. Expiry Chain Intelligence
- When a parent document expires, flag dependent documents.
- Example: passport expiring → flag visa, OCI, driver's license tied to passport number, frequent flyer accounts.
- Country-specific renewal playbooks: clicking "Renew passport" shows the country's online process, document checklist, fee, and estimated time.

### 4. Household / Family Vaults
- One billed account, multiple "profiles" (self, spouse, child, parent).
- RBAC: spouse can be co-admin; child read-only on their own docs; pediatrician can be granted view-only on child's medical sub-folder.
- Family expiry dashboard: one view of every expiry across every person.

### 5. AI Document Assistant ("Ask Your Vault")
- Natural language Q&A grounded in user's documents using RAG over `pgvector` embeddings.
- Examples: *"What's my dental coverage limit?"*, *"When did I last renew my car insurance and how much did premiums change?"*, *"Find all medical bills from 2024 above ₹5000."*
- Cites the source documents with page snippets. Never hallucinates — if not in vault, says so.

### 6. Selective Field Redaction on Share
- When generating a shareable link, the LLM-extracted fields are visible as toggles: "Share this insurance policy but **hide policy number, hide phone number, hide address**."
- The shared PDF gets the fields cryptographically blacked out, not just CSS-hidden.

### 7. Email-to-Vault Ingestion
- Every user gets `<username>.<random>@in.vaultdoc.app`.
- Forwarding an email with attachments auto-ingests them, with the email body used as context for classification.
- DKIM-verified senders (e.g., HDFC, ICICI, Indian Income Tax Dept) get auto-trusted and auto-filed.

### 8. WhatsApp/Telegram Bot Bridge
- Forward a doc photo to the bot → it appears in the vault in seconds.
- Pin reply: "Filed under Financial > Tax Returns > 2024 as Form 16. Expiry: none. ✓"
- This is where it wins against Paperless-ngx, which has zero such ergonomics.

### 9. Browser Extension (Save-from-Web)
- One click to save the current page as a PDF + capture the URL as provenance.
- Special handlers for portals: Indian Income Tax e-filing (save ITR-V), bank statements (auto-detect download links), Aadhaar UIDAI portal.

### 10. Document Tampering & Integrity
- Every ingested doc gets a SHA-256 stored at upload + a blockchain-anchored timestamp (use OpenTimestamps — free Bitcoin anchoring).
- Useful for legal disputes: prove "I had this rental agreement on this date."
- If a user re-uploads a "same" document, visual diff highlights the change.

### 11. Travel Mode (Offline Emergency Pack)
- Pre-trip, generate an AES-256-encrypted ZIP with: passport, visa, travel insurance, vaccination certs, hotel bookings, emergency contacts.
- One QR code unlocks it offline on any phone with the VaultDoc app. Critical when you're at immigration with no signal.

### 12. Digital Legacy / Inheritance
- Designate a trusted contact + a dead-man's-switch period (e.g., 30 days of inactivity → email contact a recovery code).
- Contact can request access; user gets a verification window to cancel.
- Granular: "spouse gets everything, child gets only their medical docs."

### 13. Vault Insights Dashboard
- Derived analytics from extracted fields:
  - Annual insurance premium across all policies
  - Tax-saving instrument totals (80C, etc.)
  - Properties owned, total value, total EMI
  - Vehicles owned, next service/PUC dates
  - Health: list of doctors, recent diagnoses, current prescriptions (from OCR'd prescriptions)
- This is the "and now I understand my life" moment.

### 14. Multilingual Native-Script OCR
- Tesseract with `tessdata_best` for: English, Hindi, Tamil, Telugu, Bengali, Marathi, Arabic, Mandarin.
- Smart language detection per document. Mixed-language docs handled (Indian docs often have English + Hindi).

### 15. Confidence-Driven Human-in-the-Loop
- OCR + LLM extraction returns confidence scores per field.
- Low-confidence fields (e.g., extracted expiry date with 0.6 confidence) go to a **"Needs Review"** queue with the original snippet highlighted.
- One-tap accept/edit/reject. Improves trust hugely.

### 16. "What Documents Do I Need?" Wizard
- User says "I'm applying for a Schengen visa from India" → wizard checks which required documents are in the vault, which are missing, which are expired.
- Generates a packaged ZIP labeled per the embassy's exact naming convention.

---

## Backend Module Breakdown (Spring Boot)

Use **modular monolith**, not microservices, until volume justifies splitting. Module = Gradle subproject + package boundary + no cross-module entity imports. Build tool: **Gradle Kotlin DSL** with a `buildSrc` convention plugin and a `libs.versions.toml` version catalog.

```
vaultdoc-backend/
├── core-auth/              # Spring Security, JWT, WebAuthn, 2FA, sessions
├── core-crypto/            # Envelope encryption, key derivation, OpenTimestamps
├── core-storage/           # S3 abstraction, presigned uploads, lifecycle policies
├── ingest/                 # Upload, virus scan (ClamAV), MIME validation
├── ocr/                    # Tesseract + PDFBox pipeline, language detection
├── extraction/             # LLM provider abstraction, per-type schemas
├── search/                 # Elasticsearch indexing, query DSL builder
├── catalog/                # Folders, tags, document CRUD, relationships
├── alerts/                 # Expiry detection, notification fan-out (FCM + email + web push)
├── sharing/                # Shareable links, redaction, audit
├── assistant/              # RAG, pgvector, Ask-Your-Vault
├── inbox/                  # Email-to-vault, WhatsApp/Telegram bot
├── household/              # Multi-profile, RBAC
├── billing/                # Stripe/Razorpay, plans, usage limits
└── api-gateway/            # REST controllers, OpenAPI 3, rate limiting
```

Each module exposes a Java interface; another module depends on the interface, never the impl class.

---

## Mobile App (Flutter, Android-First)

The mobile app is **not** a viewer for the web app — it is the primary entry point for most users. Camera-first ingestion, push-driven retention, offline retrieval.

### Why Flutter (and not the alternatives)

| Option | Verdict | Reason |
|---|---|---|
| **Flutter** ✅ | Chosen | `google_mlkit_document_scanner` ships Google's native ML Kit scanner (auto edge detection, perspective correction, multi-page) with one dependency. Skia rendering is fast for image work. Truly single codebase for Android + iOS later. |
| React Native | Rejected | Larger ecosystem but for camera + ML Kit + offline-encrypted storage you end up writing more native bridge code than expected. iOS parity is not free. |
| Ionic / Capacitor | Rejected | WebView underneath. Scanner UX, camera, on-device crypto feel sluggish — wrong fit for a scanner-first app. |
| Native Kotlin (Compose) only | Rejected | Best Android quality, but iOS is on the roadmap. Rebuilding scanner UX, sync engine, and crypto twice is a tax we don't need to pay. |

Dart is unfamiliar territory for a Java/Angular team but reads like cleaner Java — productive within a weekend.

### Module Structure (mirrors backend)

```
vaultdoc-mobile/
├── lib/
│   ├── core/
│   │   ├── auth/                  # Token storage, biometric unlock, passkeys
│   │   ├── crypto/                # Per-doc DEK, KEK derivation (Argon2), AES-GCM
│   │   ├── storage/               # Local encrypted DB (drift + sqlcipher)
│   │   ├── sync/                  # Background sync engine, conflict resolution
│   │   ├── network/               # Dio + OpenAPI-generated client
│   │   └── di/                    # Riverpod providers / GetIt
│   ├── features/
│   │   ├── scanner/               # ML Kit document scanner, multi-page capture
│   │   ├── upload/                # Queue, resumable upload, retries
│   │   ├── catalog/               # List, folders, search UI
│   │   ├── viewer/                # PDF + image viewer, redaction preview
│   │   ├── alerts/                # FCM, in-app expiry list, snooze
│   │   ├── assistant/             # Ask-Your-Vault chat
│   │   ├── share/                 # Share-link generation, redaction toggles
│   │   ├── household/             # Profile switcher
│   │   ├── travel_pack/           # Offline emergency pack builder
│   │   └── settings/              # Zero-knowledge toggle, BYOK keys, biometrics
│   ├── shared/
│   │   ├── widgets/
│   │   ├── theme/                 # Material 3, dark mode, dynamic color
│   │   └── l10n/                  # English + Hindi from day 1
│   └── main.dart
├── android/
└── ios/                            # Stubbed; activated in Phase 7
```

### Required Flutter Packages (Android-first)

| Concern | Package | Notes |
|---|---|---|
| Document scanner | `google_mlkit_document_scanner` | Native ML Kit, auto edge detection |
| Camera (fallback) | `camera` | If user wants raw shots |
| File picker | `file_picker` | Multi-file, MIME filtering |
| PDF rendering | `pdfx` or `syncfusion_flutter_pdfviewer` | Smooth zoom + text selection |
| Local DB | `drift` + `sqlcipher_flutter_libs` | Encrypted SQLite |
| Secure storage | `flutter_secure_storage` | KEK material in Android Keystore |
| Biometric auth | `local_auth` | Unlock vault with fingerprint/face |
| Crypto | `cryptography` (BoringSSL) | AES-GCM, Argon2id, X25519 |
| Networking | `dio` + `retrofit` | Interceptors for auth, retries |
| State | `riverpod` | Compile-safe, testable |
| Push | `firebase_messaging` | FCM for expiry alerts |
| Background work | `workmanager` | Periodic sync, alert refresh |
| Offline-first sync | `drift` + custom sync layer | Last-write-wins with vector clocks |
| OpenAPI client | `openapi_generator` (codegen) | Same spec drives Angular client |
| QR | `mobile_scanner` | Travel-pack unlock + sharing |
| OCR (zero-knowledge mode only) | `google_mlkit_text_recognition` | On-device, never leaves phone |
| Routing | `go_router` | Deep linking + auth guards |

### Android-specific setup

- `compileSdk` 35, `minSdk` 26 (covers ~95% of Indian Android devices in 2026).
- ProGuard/R8 enabled for release; rules for ML Kit + cryptography library bundled in repo.
- Adaptive icon, splash (Android 12+ splash API via `flutter_native_splash`).
- App Links for `https://app.vaultdoc.app/d/{shareToken}` deep links.
- Foreground service for active sync (notification with "Syncing 12 documents…").
- Encrypted preferences via Android Keystore + `flutter_secure_storage`.
- Storage Access Framework for "share to VaultDoc" from any other app — single most important Android-only integration. Register `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents for `application/pdf`, `image/*`.
- Manifest: `RECEIVE_BOOT_COMPLETED` to reschedule alerts after reboot.

### Mobile-Only Features (Worth Highlighting)

- **One-tap scan flow**: launcher icon shortcut → camera opens directly in scanner mode → upload + extract in background.
- **Share-sheet ingestion**: from any app (Gmail, WhatsApp, browser) → "Share to VaultDoc" → auto-classified and filed.
- **Offline vault**: configurable "always available offline" tag — those documents are encrypted and cached locally.
- **Biometric unlock**: app and individual sensitive folders ("Identity") can require fingerprint each time.
- **Quick Tile / Home screen widget**: "Next expiry: Vehicle Insurance — 12 days" — Android-only differentiator.
- **App lock on background**: defaults to lock immediately when app goes to background.

### Shared Contract: OpenAPI as Single Source of Truth

- Backend generates `openapi.yaml` at build time from Spring controllers.
- CI publishes the spec as an artifact.
- Angular consumes via `@openapitools/openapi-generator-cli` → TypeScript client.
- Flutter consumes via `openapi_generator` → Dart client.
- Breaking changes to the spec fail CI on both clients. This is the contract.

---

## Data Model Highlights

```
documents
  id (uuid), owner_user_id, profile_id (FK households.profiles),
  folder_id, original_filename, mime_type, size_bytes,
  storage_key, sha256, encrypted (bool), dek_wrapped (bytea nullable),
  ocr_status (enum: PENDING/PROCESSING/DONE/FAILED),
  extraction_status (enum), needs_review (bool),
  document_type (enum, nullable until classified),
  issued_at, expires_at, primary_party, secondary_parties (jsonb),
  uploaded_at, updated_at

document_fields                          # generic typed key-values per doc
  document_id, field_key, field_value, value_type, confidence

document_relationships                   # graph edges
  from_document_id, to_document_id, relationship_type, source (auto/user)

document_versions                        # tampering detection + diffs
  document_id, version, sha256, opentimestamps_proof, created_at

embeddings                               # pgvector
  document_id, chunk_index, chunk_text, embedding vector(768)

shares
  id, document_id, token_hash, expires_at, max_views, view_count,
  password_hash, watermark_recipient, redaction_config (jsonb)

alerts
  id, document_id, alert_type, fire_at, lead_days, channel, status

household_profiles
  id, owner_user_id, display_name, dob, relation, avatar_url

profile_acl
  profile_id, grantee_user_id, scope (folder_id or '*'), permission

ingest_inbox_addresses
  user_id, alias_local_part, alias_secret, created_at

reviews
  document_id, field_key, suggested_value, status (PENDING/ACCEPTED/REJECTED)

device_registrations                     # for FCM + sync coordination
  id, user_id, platform (ANDROID/IOS/WEB), fcm_token, last_seen_at
```

---

## API Surface (Selected)

All under `/api/v1`. OpenAPI 3 spec is the source of truth — both frontends generate clients from it.

```
POST   /documents:initiate-upload         # returns presigned S3 PUT
POST   /documents:finalize                # triggers OCR+extraction pipeline
GET    /documents                         # filters: type, folder, tag, expiry_window, q
GET    /documents/{id}                    # full doc + extracted fields + relationships
PATCH  /documents/{id}/fields             # human-in-loop edits
POST   /documents/{id}/relationships
POST   /documents/{id}/shares             # body: expiry, redaction, watermark
DELETE /shares/{token}                    # revoke
GET    /search?q=...&filters=...
POST   /assistant/ask                     # body: { question }, returns answer + citations
GET    /vault/insights                    # aggregated analytics
GET    /expirations?within=30d
POST   /alerts/preferences
POST   /household/profiles
POST   /wizards/visa?destination=schengen # what-do-I-need wizard
POST   /travel-pack                       # build offline encrypted bundle
POST   /inbox/email-alias:regenerate
POST   /legacy/configure                  # digital inheritance
POST   /devices                           # mobile: register FCM token
GET    /sync/changes?since=<cursor>       # mobile: delta sync
```

---

## Processing Pipeline (Redis Streams)

```
upload-finalized → virus-scan → ocr → language-detect → text-chunk
                                                           ↓
                                                  embedding-generate
                                                           ↓
                                                  llm-classify-and-extract
                                                           ↓
                                                  index-elasticsearch
                                                           ↓
                                                  detect-relationships
                                                           ↓
                                                  schedule-expiry-alerts
                                                           ↓
                                                  notify-user (FCM + email + web push)
```

Each stage is idempotent. Failed stages move to a dead-letter stream with replay UI. Workers use **Java 21 virtual threads** — one consumer per stream, fan-out by partition.

---

## LLM Extraction Schema Pattern

Per document type, a JSON Schema + few-shot prompt + Pydantic-style validator. Example:

```yaml
document_type: INDIAN_PASSPORT
required_fields:
  - passport_number (string, regex: ^[A-Z][0-9]{7}$)
  - surname (string)
  - given_names (string)
  - date_of_birth (date)
  - date_of_issue (date)
  - date_of_expiry (date)
  - place_of_issue (string)
optional_fields:
  - file_number
  - father_name
prompt_template: |
  You are extracting fields from an Indian passport. The OCR text is below.
  Return JSON matching the schema. If a field is illegible, return null and
  include it in `low_confidence_fields`. Never invent values.
```

Schemas to ship in v1: Aadhaar, PAN, Driving License, ITR-V, Form 16, Bank Statement (HDFC/ICICI/SBI templates), LIC Policy, Health Insurance, Rental Agreement, Sale Deed, Vehicle RC, PUC Certificate, Medical Prescription, Lab Report, Vaccination Certificate, Marksheet (CBSE/ICSE/State Board), Degree Certificate.

---

## Development Phases

**Phase 1 — Vault Foundation (3 weeks)**
Backend auth (incl. passkeys), upload, S3 storage, OCR pipeline, basic search, folder tree. Angular shell with upload + search + viewer. Flutter project skeleton (no features yet, just login + list).

**Phase 2 — Intelligence Layer (3 weeks)**
LLM extraction with confidence + review queue, expiry alerts, document type schemas (top 10), Elasticsearch indexing, full-text search UI with highlights. Mobile gets scanner + upload + list working.

**Phase 3 — Unique Features Part 1 (3 weeks)**
Zero-knowledge mode (toggle per account), document relationships graph, household profiles + RBAC, expiry chain intelligence. Mobile gets FCM push + biometric unlock.

**Phase 4 — Unique Features Part 2 (3 weeks)**
Ask-Your-Vault (RAG), email-to-vault, Telegram bot, browser extension, selective redaction, shareable links. Mobile gets share-sheet ingestion + offline tag.

**Phase 5 — Polish & Differentiation (2 weeks)**
Travel pack, digital legacy, vault insights, document wizards, OpenTimestamps tamper proofs. Mobile home screen widget + quick tile.

**Phase 6 — Productization (2 weeks)**
Billing (Stripe + Razorpay), self-host Docker Compose + Helm chart, onboarding flow, marketing site, documentation.

**Phase 7 — iOS Parity (2 weeks)**
Enable Flutter iOS target, integrate Apple Sign In, replace FCM-only flow with APNs, App Store submission. The "iOS later" promise made good.

---

## Quality Bar (Non-Negotiable)

- **Tests**: ≥80% line coverage on core modules. Backend integration tests use Testcontainers (PostgreSQL, Redis, Elasticsearch, MinIO) — no mocks for DB. Flutter: widget tests + golden tests for scanner/viewer flows.
- **Security**: OWASP ASVS L2. Every endpoint passes through authz checks; never trust `userId` from request body. Rate limit auth endpoints. CSP headers. No secrets in code — use environment + Spring Cloud Config. Mobile pins TLS certs.
- **Observability**: every request has a trace ID. OCR pipeline emits structured events. P95 latency dashboards from day 1. Mobile crash reporting via Sentry.
- **Migrations**: Flyway only. Every migration is reversible or has a documented rollback.
- **API**: OpenAPI 3 spec generated from controllers, published at `/api/docs`. Breaking changes require a new version. CI verifies Angular and Flutter clients still compile.
- **Frontend**: Lighthouse score ≥90 mobile. Accessible (WCAG 2.2 AA). i18n-ready (English + Hindi from day 1).
- **Mobile**: cold start <2s on a mid-range Android (e.g., Pixel 6a / Samsung A55). APK size <40MB. Crash-free sessions >99.5%.
- **Self-host story**: `docker compose up` brings up the whole stack on a fresh machine, including seed data. README has a 5-minute install path.

---

## Anti-Patterns to Avoid

- **Don't** silently fail OCR — surface it. Users must trust the system.
- **Don't** ship microservices on day 1. Modular monolith until you've earned the right to split.
- **Don't** use a vector DB separately. `pgvector` is enough until 10M+ chunks.
- **Don't** invent document types automatically without schema review. Hallucinated types corrupt the catalog.
- **Don't** store LLM provider API keys server-side for self-host users — let them BYOK.
- **Don't** roll your own crypto. Tink or BouncyCastle on backend, the `cryptography` package on Flutter.
- **Don't** add features beyond this list until the core works end-to-end. Discipline matters.
- **Don't** make the mobile app a thin wrapper around the web app. It's a first-class client.

---

## When You Begin (Instructions to Claude Code)

1. Read this entire file before writing any code.
2. The existing Gradle/Kotlin scaffold has been replaced with a Spring Boot **Gradle Kotlin DSL** multi-module project under `backend/`, per the module breakdown above. The repo is a monorepo: `backend/`, `web/`, `mobile/`, `infra/`, `openapi/` all live side-by-side.
3. Propose a Phase 1 plan: module skeletons, DB schema for Phase 1 tables only, Angular shell structure, Flutter project skeleton. Get explicit approval before scaffolding.
4. Default to small PRs per feature. Every PR has a description, screenshots if UI, and a test plan.
5. Surface ambiguities early. If a unique feature has unclear UX, sketch two options in ASCII and ask.
6. The first user is the project owner. The interface should be one they'd use daily without rolling their eyes.

---

## At Project Completion — Required Deliverable Docs

Once the project (through at least Phase 6) is built and running end-to-end, **produce two more documents at the repo root**:

### `ARCHITECTURE.md`
A polished architecture document for engineers joining the project. It must include:
- One-paragraph system summary.
- High-level architecture diagram (ASCII or Mermaid) showing: clients (web, mobile, browser ext, bots) → API gateway → modular monolith → data plane (Postgres, Elasticsearch, Redis, S3) → external services (LLM, ClamAV, OpenTimestamps, FCM).
- Module-by-module breakdown with each module's responsibility, key classes, and dependencies.
- Data flow walkthroughs for: (a) document upload + extraction, (b) expiry alert delivery, (c) Ask-Your-Vault query, (d) zero-knowledge upload from mobile.
- Security model: auth flow, encryption layers, threat model summary.
- Mobile architecture: state management, sync engine, offline strategy, encryption.
- Scaling notes: where the bottlenecks live, what to split out first when traffic grows.
- ADRs (Architecture Decision Records) for the five biggest choices (modular monolith, Flutter, pgvector, OpenTimestamps, BYOK).
- Diagrams should be Mermaid where possible so they render on GitHub.

### `README.md`
A polished public-facing README. Tone: confident, concise, screenshot-rich. Sections:
- Tagline + 2-sentence pitch.
- Animated GIF or screenshot grid (scanner, search, expiry dashboard, AI assistant).
- Why VaultDoc (vs Paperless-ngx, Google Drive, Evernote) — a quick comparison table.
- Feature highlights (top 8 differentiators, one line each).
- Quick start: `docker compose up` for self-host, link to SaaS waitlist.
- Mobile app: Play Store badge (when published), instructions to side-load APK in beta.
- Tech stack badges.
- Project status + roadmap (what's done, what's next, what's wishlist).
- Contributing guide link, license (AGPL-3.0 recommended for self-host moat).
- Link to ARCHITECTURE.md for engineers.

Both files are part of the "definition of done." A reader should be able to land on the README and either install the system or understand it in five minutes; an engineer should be able to land on ARCHITECTURE.md and contribute meaningfully in a day.
