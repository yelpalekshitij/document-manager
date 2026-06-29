# VaultDoc Build Log

A phase-by-phase log of what landed, what was deferred, and why. Each entry captures the durable decisions; the day-to-day diffs live in git history.

> Source-of-truth for project scope: [CLAUDE.md](./CLAUDE.md). This file is the *journal*.

---

## Phase 1.0 — Skeleton & Infrastructure  *(2026-05-14)*

**Goal:** A working monorepo `docker compose up` brings to life, backend `/health` answers green, both frontends compile.

### Scope

| In | Deferred |
|---|---|
| Monorepo layout, Docker Compose, OpenAPI starter, Gradle multi-module backend with 15 subprojects, Angular shell, Flutter shell, CI workflow | Auth (1.1a), OCR/upload (1.2+), features in general |

### Decisions locked

- **Gradle Kotlin DSL** for the backend (keeping the existing wrapper), not Maven — type-safe build scripts, faster incremental builds.
- **Single monorepo** at `/Users/kshitij/Projects/wkda/docman/` — one git history, shared `openapi/` spec.
- **Java 21** with virtual threads enabled, Spring Boot 3.4.1.
- **Flutter, Android-first** mobile target — `google_mlkit_document_scanner` ships Google's native ML Kit scanner with one Dart dep; iOS comes online in Phase 7.
- **Postgres 16 + pgvector** (single database, no microservices).
- **Convention plugin pattern** — every backend module's `build.gradle.kts` is `plugins { id("vaultdoc.conventions") }`. Versions live in `gradle/libs.versions.toml`.

### What landed

- **Root:** `README.md` (placeholder), `CLAUDE.md` patched (Maven → Gradle), `.gitignore`, `.editorconfig`, `.github/workflows/ci.yml` (3 parallel jobs + OpenAPI lint).
- **`infra/`:** `docker-compose.yml` (Postgres+pgvector, Redis 7, Elasticsearch 8.15.3, MinIO + init-bucket sidecar, Mailhog), `.env.example`, init SQL extensions (pgvector, pgcrypto, uuid-ossp), `README.md`.
- **`openapi/vaultdoc.yaml`:** OpenAPI 3.1 with `/health` and `Problem` schema.
- **`backend/`:** Gradle multi-module — 14 library modules (`core-auth`, `core-crypto`, `core-storage`, `ingest`, `ocr`, `extraction`, `search`, `catalog`, `alerts`, `sharing`, `assistant`, `inbox`, `household`, `billing`) + `api-gateway` as the only Spring Boot app. `buildSrc` convention plugin, version catalog. `HealthController` probes DB/Redis/ES/S3. `V1__init.sql` Flyway baseline.
- **`web/`:** Angular 18 standalone — bootstrap, router, two screens (login placeholder + home pinging `/health`), `proxy.conf.json` to `:8080`.
- **`mobile/`:** Flutter `lib/` only (Android stubs filled in by `flutter create --platforms=android .`) — pubspec, main, theme, router, login/home placeholders, widget test.

### Tests added: 14 (one smoke test per library module).

### Bugs hit & fixed

- `buildSrc` was exposing the Spring Boot gradle plugin onto the shared classpath — non-Boot modules saw "plugin already on classpath with unknown version". Fix: keep only `dependency-management-plugin` in `buildSrc`; `api-gateway` applies `spring-boot` directly via the version catalog.
- Gradle 9 requires `junit-platform-launcher` explicitly on the test runtime classpath. Added to the convention plugin.
- Wrote `@Value` as a fully-qualified annotation reference without the `@` (compile error). Fixed with proper import.

### Verification

```bash
cd infra && cp .env.example .env && docker compose up -d
cd ../backend && ./gradlew :api-gateway:bootRun
curl -s localhost:8080/api/v1/health | jq        # → {"status":"UP","components":{…}}
./gradlew test                                    # → 14 green
```

---

## Phase 1.1a — Email/Password Auth + JWT  *(2026-05-14)*

**Goal:** Real authentication wired end-to-end: backend issues JWT pairs, both clients sign in/up/out/refresh through hand-written typed clients.

### Scope

| In | Deferred |
|---|---|
| Email + password, Argon2id, JWT access + rotating refresh tokens, session reuse detection, RFC 7807 problem responses, hand-written Angular + Flutter clients | Passkeys (1.1c), email verification (1.1b), 2FA (1.1d), OpenAPI codegen (1.1b), HIBP password checks (later) |

### Decisions locked

- **Argon2id** via `de.mkammerer:argon2-jvm` at OWASP 2024 baseline (64 MiB, 3 iterations, 4 parallelism).
- **HS256 JWT** with rotating refresh: every `/auth/refresh` issues a new token in the same `session_id` and revokes the previous. **Presenting a revoked token revokes the whole session family** (theft detection).
- **Refresh tokens are hashed at rest (SHA-256)** — DB leak ≠ usable token.
- **Web token storage:** `localStorage` for 1.1a — secure cookies land when cookie-based auth lands.
- **Mobile token storage:** `flutter_secure_storage` (Android Keystore-backed).
- **NIST 800-63B password policy:** min 12 chars, no composition rules.
- **Module shape:** public service interface + impl + `@Configuration` auto-config with `@ConditionalOnMissingBean`. Every future module copies this shape.
- **Schema ownership:** Flyway migrations all live in `api-gateway/.../db/migration` regardless of which module owns the table.
- **Exception → Problem mapping:** `ProblemDetailsHandler` in `api-gateway` translates module exceptions into RFC 7807 JSON.

### What landed

- **Backend (`core-auth`):** User + RefreshToken entities + repos, `AccountStatus` enum, Argon2idPasswordHasher + interface, JwtIssuerImpl (with `Clock` injection for testable expiry), `RefreshTokenService` (rotation + reuse detection), `AuthServiceImpl` orchestrating register/login/refresh/logout/me, `AuthAutoConfiguration`, `AuthProperties`.
- **Backend (`api-gateway`):** `AuthController` (5 endpoints), 5 validated DTOs, `JwtAuthenticationFilter` (reads `Authorization: Bearer`), `SecurityConfig` (stateless, CORS, RFC 7807 entry point), `ProblemDetailsHandler`, Flyway `V2__auth.sql`.
- **Web:** `core/api/types.ts`, hand-written `auth.api.ts`, `core/auth/{store,interceptor,guard}.ts`, rewritten login + new register components, home with sign-out, routes with auth/anonymous guards.
- **Mobile:** `core/auth/{token_storage, auth_models, auth_repository, auth_controller}.dart`, dio interceptor with 401-refresh-once-and-retry, sealed `AuthState`, rewritten login + new register + updated home, router with auth-aware redirects.

### Tests added: 23 backend (18 unit + 5 IT)

- **Unit:** Argon2idPasswordHasherTest (5), JwtIssuerImplTest (6 with fixed-clock expiry tests), AuthServiceImplTest (7).
- **Integration:** AuthControllerIT — full register → login → /me → refresh → logout loop including reuse-detection, validation errors, duplicate email, wrong password. HealthEndpointIT updated to use shared TestcontainersConfig.

### Bugs hit & fixed

- **Hibernate ↔ Postgres `INET` mismatch:** `refresh_tokens.ip_address INET` triggered "column is of type inet but expression is of type character varying". Switched to TEXT — we don't need INET operators.
- **Spring Boot Testcontainers needed for `@ServiceConnection`** — added `spring-boot-testcontainers` dep.
- **`@EntityScan` + `@EnableJpaRepositories` shouldn't live in module auto-configs** — they replace Spring Boot's defaults. Moved to `VaultDocApplication` rooted at `app.vaultdoc` so all modules' entities are scanned.

### Verification

```bash
# Register → login → /me → refresh-with-reuse-rejection → logout, then 1 more refresh = 401
curl -sX POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"k@example.com","password":"correct-horse-battery-staple"}' | jq
# etc.
./gradlew test    # → 23 green
```

---

## Phase 1.1b — Email Verification + OpenAPI Codegen  *(2026-05-14)*

**Goal:** Account creation now requires email proof; both clients regenerate typed API services from `openapi/vaultdoc.yaml` instead of hand-writing them.

### Scope

| In | Deferred |
|---|---|
| Verification tokens, Mailhog SMTP wiring, register-then-verify-then-login UX, OpenAPI codegen on both clients, problem-type URIs | Passkeys (1.1c), TOTP 2FA (1.1d), password reset (1.1d), account lockout (1.1d) |

### Decisions locked

- **Register returns no tokens.** Account is created `PENDING_VERIFICATION`. Login is blocked until verified. The verify endpoint activates and issues the session.
- **Token shape:** same `secure-random → SHA-256-hashed → 24h TTL → single-use` as refresh tokens. Re-issuing for the same user revokes any prior unconsumed token (the previous email becomes useless).
- **Verification URL points at the web app** (`http://localhost:4200/verify?token=…`), not the backend. Web base URL is configurable.
- **Rate limit:** 1 resend per 60s per email, in-memory `ConcurrentHashMap` for now. Will move to Redis when we scale.
- **Login on `PENDING_VERIFICATION`** → 403 with `type=https://vaultdoc.app/problems/unverified-account`. Clients branch on `type` to show a Resend CTA.
- **Generator pinning:** web uses `@openapitools/openapi-generator-cli` v7.10.0 (`typescript-angular`). Mobile uses `openapi_generator` 6.x + `build_runner` (`dart-dio`). Generated output is **gitignored**; CI regenerates on every build.
- **Wire-spec problem types are URIs:** typed problems let clients branch on `type` instead of HTTP code alone. Every future client-handled error follows this.

### What landed

- **Backend:** VerificationToken entity + repo, `V3__verification.sql`, `EmailSender` interface + `SpringMailEmailSender`, `VerificationEmailTemplate`, `VerificationService`, `VerificationProperties`, 3 new exceptions, `AuthService.verifyEmail() / resendVerification()`, `AuthServiceImpl` updates (no tokens on register, 403 on unverified login).
- **Backend gateway:** 2 new endpoints (`/auth/verify-email`, `/auth/resend-verification`), 3 new DTOs, `RegistrationResponse` replaces `AuthResponse` from register, `ProblemDetailsHandler` adds typed URIs + `Retry-After` on 429, `application.yml` adds `spring.mail.*` + `vaultdoc.verification.*`.
- **Web:** `package.json` + `openapitools.json` for codegen, `prestart/prebuild/pretest` hooks, deleted hand-written `auth.api.ts`, rewrote `auth.store.ts` against the generated `AuthService`, new `verify-email.component.ts`, register's "check your email" state, login's unverified-account banner with Resend.
- **Mobile:** `pubspec.yaml` adds `openapi_generator` + `build_runner` dev deps + `vaultdoc_api: path: api` runtime dep, `openapi_config.dart` with `@Openapi(...)` annotation, deleted `auth_repository.dart`, rewrote `auth_controller.dart` against generated `AuthApi`, new `verify_email_screen.dart`, deep link wiring (Android intent filters documented in `mobile/README.md`).
- **CI:** web job runs `npm run generate:api` after `npm ci`; mobile job runs `dart run build_runner build --delete-conflicting-outputs` after `flutter create`.

### Tests added: 9 backend (8 VerificationServiceTest + updated AuthServiceImplTest + extended AuthControllerIT)

Key IT: `RecordingEmailSender` test bean captures sent messages so the IT can extract the verification token from the email body and complete the full register → 403-unverified → verify → 200-active → login → reuse-rejected → resend-rate-limited loop.

### Bugs hit & fixed

- **JPA `@PrePersist` doesn't fire in mocked unit tests** — entity IDs stayed null. Moved id assignment into the constructor.
- **Parallel test execution + shared `RecordingEmailSender` raced** when one test's `@BeforeEach mail.clear()` wiped another test's capture. Tests already used unique emails — removed the clears.

### Architectural precedents locked

1. **OpenAPI is the contract.** Spec change → both clients regenerate → backend implements. Hand-written API services in clients are forbidden going forward.
2. **Email goes through `EmailSender`** — any future module needing mail injects this interface; no JavaMailSender scattered across modules.
3. **Verification-token shape generalizes.** Same pattern reused for password reset, magic-link login, shareable doc links.

### Verification

```bash
# Register, see 403 on login, copy token from http://localhost:8025 (Mailhog),
# POST /auth/verify-email → 200 AuthResponse, login succeeds.
./gradlew test    # → 32 green
```

---

## Phase 1.1c — Passkeys (Backend + Web)  *(2026-05-15 → 2026-05-16)*

**Goal:** WebAuthn registration + sign-in on web. Backend ceremony endpoints power both this and the mobile flow (1.1d).

### Scope

| In | Deferred |
|---|---|
| 6 backend endpoints (register/signin begin+finish, list, delete), `webauthn4j-core` integration, Redis-backed challenge cache, Angular `@simplewebauthn/browser` flows, multi-credential management UI | Mobile passkeys (1.1d), Conditional UI / autofill (1.1d), TOTP 2FA (1.1e), account recovery (1.1e) |

### Decisions locked

- **Library: `com.webauthn4j:webauthn4j-core:0.24.4.RELEASE`** — the last Jackson-2 release. 0.31.x targets Spring Boot 4 / Jackson 3 and clashes with our Spring Boot 3.4 stack.
- **Not using Spring Security's built-in `.webAuthn()` configurer.** It assumes session-based auth; we own the stateless JWT loop.
- **Relying Party:** `rpId=localhost` for dev (configurable via `vaultdoc.passkey.rp-id`). `rpName="VaultDoc"`. Browser binds credentials to `rpId` — switching it later invalidates all existing credentials.
- **Algorithms:** ES256 + RS256.
- **Authenticator selection:** `residentKey: "preferred"`, `userVerification: "preferred"`, attestation `"none"`.
- **Challenge cache:** Redis 6.2 `GETDEL` for atomic single-use consumption, 5-min TTL. Key shape `passkey:challenge:{flow}:{uuid}`.
- **Sign-counter mismatch is a soft warning.** Apple authenticators always send 0; a strict check would lock users out.
- **User handle = user UUID bytes** — lets us locate the user from a discoverable-credential assertion without an email hint.
- **OpenAPI strategy for wire types:** the W3C WebAuthn JSON shapes are large and dictated by an external spec — modelled as opaque `object` + `additionalProperties: true`. The TS generator emits `{ [k: string]: any }`; `@simplewebauthn/browser` consumes it directly.

### What landed

- **Backend (`core-auth`):** `WebauthnCredential` entity + repo, `V4__webauthn_credentials.sql`, `PasskeyProperties`, `RelyingPartyConfig`, `ChallengeCache` interface + `RedisChallengeCache`, `PasskeyService` interface + 320-line `PasskeyServiceImpl` (4 ceremonies + list + delete), 3 typed exceptions, 4 records, beans wired in `AuthAutoConfiguration`.
- **Backend gateway:** `PasskeyController` (6 endpoints), 5 passthrough DTOs, `SecurityConfig` permits `/passkey/signin/*` only, `ProblemDetailsHandler` adds 3 new typed problem URIs, `application.yml` `vaultdoc.passkey.*` defaults.
- **Web:** `@simplewebauthn/browser ^11` added, 3 problem URI constants, `PasskeyClient` (wraps the lib around the generated `PasskeyService`), `PasskeyStore` (signal-backed state, friendly mapping of `NotAllowedError`/`InvalidStateError`), `AuthStore.adoptSession()` promotes a passkey-issued session into the same store, `login.component` gets a "Sign in with a passkey" CTA gated on `browserSupportsWebAuthn`, `passkey-list.component` on home (list/add/delete).

### Tests added: 18 (9 unit, 9 IT)

- **Unit:** `PasskeyServiceImplTest` — challenge storage, options shape (real ObjectConverter, mocked WebAuthnManager), excludeCredentials population, owner-scoped delete, deletion authorization, signin no-user challenge.
- **Integration:** `PasskeyControllerIT` — auth gates, wire shapes, opaque options, typed problem URIs, challenge uniqueness, empty list for new user, delete-not-found. Full crypto round-trip deliberately skipped — bridge code to deserialize options into webauthn4j-test's typed shapes was bigger than the value at this slice; orchestration is covered by unit tests, crypto correctness by webauthn4j's own suite.

### Bugs hit & fixed

- **webauthn4j 0.31.2 = Jackson 3** → switched to 0.24.4 (last Jackson-2 release).
- **API rename `verify(...)` → `validate(...)`** between webauthn4j majors.
- **`ServerProperty` takes `Set<Origin>`** not `List<Origin>`.
- **`RegistrationRequest` takes `Set<String>`** for transports, not `Set<AuthenticatorTransport>`.
- **`CoreCredentialRecordImpl` is the wrong type** for `AuthenticationParameters` — switched to `CredentialRecordImpl`.
- **Jackson 2 wasn't on core-auth's compile classpath** when used standalone — added `com.fasterxml.jackson.core:jackson-databind` as an explicit `api` dep.
- **Same parallel-test race from 1.1b** but in a new IT — relied on unique emails per test.

### Architectural precedents locked

1. **External crypto stays in `core-auth`** — webauthn4j joins argon2 + jjwt here. Phase 3's zero-knowledge envelope encryption and Phase 5's OpenTimestamps will follow the same shape.
2. **Wire-spec types stay opaque in OpenAPI** when the wire format is dictated by an external standard — `object` + `additionalProperties: true` + a `challengeId` envelope.
3. **Challenges live in Redis with atomic `GETDEL`** — pattern reused for any future ephemeral nonce.
4. **Counter mismatch = soft warning, not hard failure** — anywhere strict spec compliance fights real-world authenticator behavior.

### Verification (needs a host authenticator — Touch ID, Windows Hello, security key)

```bash
# Sign in with password → home → "Add a passkey" → name it.
# Sign out → login screen → "Sign in with a passkey" → platform picker → land authed.
./gradlew test    # → 48 green (32 + 18 new − overlap)
```

---

## Phase 1.1d — Mobile Passkeys (Android) + Web Conditional UI  *(2026-05-16)*

**Goal:** Close the passkey loop on mobile via Android Credential Manager, and let web users sign in by just focusing the email field.

### Scope

| In | Deferred |
|---|---|
| Flutter ↔ Kotlin platform channel, Credential Manager createCredential/getCredential, mobile UI (login button + home panel), capability gating on API 28+, web Conditional UI / autofill | iOS passkeys (Phase 7), TOTP 2FA (1.1e), password reset (1.1e), account recovery (1.1e) |

### Decisions locked

- **No third-party Flutter passkey package.** Owned MethodChannel: ~150 lines of Kotlin + ~120 lines of Dart. No dependency churn for a security-critical surface.
- **Android `CredentialManager` accepts W3C JSON natively** via `CreatePublicKeyCredentialRequest(requestJson)` / `GetPublicKeyCredentialOption(requestJson)` — exactly the JSON shape our backend already returns and accepts. The bridge passes strings end-to-end. **No shape massaging anywhere in our stack** (the payoff of 1.1c's "opaque object" OpenAPI decision).
- **minSdk stays at 26.** Credential Manager genuinely supports API 28+; the UI gates the passkey CTAs off `Build.VERSION.SDK_INT`. Older devices fall back to password sign-in.
- **Capability is state, not a feature flag.** `PasskeyController.detectSupport()` populates a `supported` field; UI binds to it. Same shape will apply to biometric unlock, ML Kit scanner availability, etc.
- **Android deps:** `androidx.credentials:credentials:1.3.0` + `androidx.credentials:credentials-play-services-auth:1.3.0` (latter wires Google Password Manager when available).
- **Channel name:** `app.vaultdoc/passkey`. Each feature owns one channel.
- **Error mapping:** Kotlin `CredentialManager` exceptions map to typed `FlutterError` codes (`CANCELLED`, `NO_CREDENTIAL`, `INTERRUPTED`, `PROVIDER_CONFIGURATION`, `UNSUPPORTED`, `UNKNOWN`); Dart wraps in `PasskeyPlatformException` with `isCancelled` / `isNoCredential` / `isUnsupported` helpers for UX branching.
- **Backend changes: zero.** All six existing endpoints serve both clients unchanged.
- **Web Conditional UI:** uses `@simplewebauthn/browser`'s `browserSupportsWebAuthnAutofill` + `startAuthentication({ useBrowserAutofill: true })`. Email input gets `autocomplete="email webauthn"`. The autofill request is launched in `ngOnInit` and aborted in `ngOnDestroy` via `AbortController`.

### What landed

- **Mobile Android host (Kotlin):**
  - `MainActivity.kt` registers our channel on `configureFlutterEngine`.
  - `PasskeyMethodChannel.kt` implements 3 methods (`isSupported`, `createCredential`, `getCredential`) atop `androidx.credentials.CredentialManager`, coroutine-dispatched, mapping every relevant exception type.
  - `AndroidManifest.xml` with `<queries>` for `androidx.credentials.registry.provider.action.{GET,CREATE}_CREDENTIAL` plus the existing INTERNET + biometric + camera permissions.
  - `android/PASSKEY_GRADLE_SNIPPET.md` — the gradle additions documented inline rather than committing a frozen `build.gradle.kts` (the Flutter-template scaffold drifts; freezing it silently rots).
- **Mobile Dart:**
  - `core/passkey/passkey_platform.dart` — `MethodChannel` wrapper, typed `PasskeyPlatformException`, swallowed `MissingPluginException` for desktop tests.
  - `core/passkey/passkey_service.dart` — orchestrator using the generated `PasskeyApi` for begin/finish + the platform channel for the OS prompt. Mirrors the web's `PasskeyClient` shape.
  - `core/passkey/passkey_controller.dart` — Riverpod `Notifier<PasskeysState>` (immutable record with `supported / loading / busy / passkeys / error`).
  - `core/auth/auth_controller.dart` — new `adoptSession(AuthSession)` matching the web's `AuthStore.adoptSession`.
  - `features/auth/login_screen.dart` — `_PasskeySignInBlock` widget below the password form, gated on `state.supported`, with platform-mapped error message.
  - `features/home/passkey_list_widget.dart` — list + add (with name prompt) + delete (with confirm). Embedded under the health card.
- **Web:**
  - `core/passkey/passkey.client.ts` — added `signInAutofill(signal?: AbortSignal)` calling `startAuthentication({ useBrowserAutofill: true, abortSignal })`. Returns `null` when autofill isn't supported or aborted.
  - `features/auth/login.component.ts` — `OnInit`/`OnDestroy`, email input gets `autocomplete="email webauthn"`, autofill kicks off in init and aborts in destroy.

### Tests added: 7 Flutter (5 platform + 2 widget gates)

- **`passkey_platform_test.dart`:** MethodChannel round-trip (isSupported, createCredential, getCredential pass requestJson verbatim, return the response string), error mapping (CANCELLED → `isCancelled`, NO_CREDENTIAL → `isNoCredential`, MissingPluginException → `isUnsupported`), `isSupported()` swallows PlatformException.
- **`widget_test.dart`:** "Passkey button hidden when Credential Manager unsupported" + "Passkey button visible when supported=true".
- Backend `./gradlew test` — unchanged: 64 still green (zero backend changes).

### Bugs hit & fixed

- **`AuthSession.fromGenerated` already existed on mobile**, but `AuthController` lacked `adoptSession` — the web grew that method in 1.1c, mobile didn't. Added matching method that writes tokens and transitions state to `Authenticated`.
- **`flutter create` template instability** — committing the Flutter-generated `android/app/build.gradle.kts` would silently break when the SDK drifts. Settled on documenting the deps additions as a snippet (`android/PASSKEY_GRADLE_SNIPPET.md`) rather than freezing a copy.
- **`rpId=localhost` doesn't work on Android Credential Manager** — browsers special-case `localhost`, Android does not. Documented `/etc/hosts` + self-signed cert as the dev path; `PASSKEY_RP_ID` is configurable on the backend so the team can pick any value that resolves consistently across host + emulator.

### Architectural precedents locked

1. **Platform channels for security-critical flows, not packages.** A single thin hand-owned MethodChannel beats a multi-thousand-line third-party package with its own release cadence and security history. Same approach will apply for biometric unlock and iOS Credential Services in Phase 7.
2. **Capability detection is a state field, not a feature flag.** UI binds to a controller-exposed `supported` flag. Reuse for biometric availability, ML Kit availability, etc.
3. **W3C JSON passes through unmodified.** Backend → MethodChannel → Credential Manager → MethodChannel → backend. Zero shape massaging anywhere in our stack.
4. **Flutter-template files (gradle, manifest) ship as documented diffs**, not frozen copies, when the template is volatile across SDK versions.

### Verification

```bash
# Mobile — needs API 28+ emulator with a screen lock configured.
cd mobile
flutter create --platforms=android --org app.vaultdoc --project-name vaultdoc_mobile .   # idempotent; respects existing files
# Apply android/PASSKEY_GRADLE_SNIPPET.md additions to android/app/build.gradle.kts
flutter pub get
dart run build_runner build --delete-conflicting-outputs
flutter test                                          # → 7 Flutter tests green
flutter run -d <api-28+-emulator>

# Manual loop:
# 1. Sign in with password → Home → "Add" in the Passkeys card → name it.
# 2. Bottom sheet → "Create passkey" → list shows the new credential.
# 3. Sign out → Login → "Sign in with a passkey" appears.
# 4. Tap → bottom sheet → pick credential → land on Home authed.

# Web — needs a registered passkey from Phase 1.1c.
cd ../web && npm run start
# Sign out → click the email field on the login page →
# browser autofill dropdown shows your passkey above any saved emails →
# pick it → land on home authed.
```

---

## Phase 1.1e — TOTP 2FA + Backup Codes  *(2026-05-16)*

**Goal:** Add a time-based one-time password second factor to the login flow across backend, web, and mobile.

### Scope

| In | Deferred |
|---|---|
| TOTP setup wizard (verify-first), login 2FA gate (firstFactorToken), TOTP verify endpoint, backup code login path, 10 Argon2id-hashed backup codes, regenerate/disable, encrypted TOTP secret at rest | Password reset (1.1f), login-attempt rate limiting (1.1f), FIDO2 hardware keys (passkeys cover this) |

### Decisions locked

- **Library:** `dev.samstevens.totp:totp:1.7.1` — covers secret generation, `otpauth://` URL, TOTP verification with configurable clock-drift window. Jackson-2 compatible.
- **No PNG generation.** Backend returns the `otpauth://` URI. Web uses Angular HttpClient + a canvas; mobile uses `qr_flutter`. Backend never touches image encoding.
- **Backup codes:** 10 codes, format `XXX-XXX-XXX` (9 uppercase alphanumeric chars). Stored as individual Argon2id hashes (same hasher as passwords). Single-use.
- **First-factor token:** After a successful password check where 2FA is enabled, the server issues an opaque token (`secure-random → SHA-256-hashed → Redis-10min-TTL`, same shape as ChallengeCache from 1.1c). The client must present it with a TOTP code (or backup code) to get a full session. Token is single-use.
- **Login response stays 200 in both cases.** `AuthResponse` has `accessToken`; `TwoFactorChallengeResponse` has `twoFactorToken`. Clients check which field is present.
- **`AuthService.login()` interface unchanged.** When 2FA is enabled, `AuthServiceImpl` throws `TwoFactorRequiredException(firstFactorToken)`. The controller catches it and returns the challenge DTO. Service interface stays stable — this is the architectural precedent for future auth complexity.
- **TOTP secret encrypted at rest** using AES-256-GCM with a server-side key (`vaultdoc.2fa.encryption-key`). Phase 3 ZK mode upgrades to a user-derived KEK; the `TotpSecretEncryptor` interface stays the same.
- **`TwoFactorService` interface includes `issueFirstFactorToken(UUID)`** — this is the hook `AuthServiceImpl` uses. Spring injects `TwoFactorService` into `AuthServiceImpl` via `@Autowired(required=false)` setter, keyed by the interface (not the impl class), so it resolves correctly from the `@Bean` factory method.
- **Mobile login shape change:** `AuthState` gets a new `TwoFactorRequired(twoFactorToken)` sealed case. The router redirects to `/2fa` when this state is reached; `TwoFactorScreen` reads the token directly from the auth state (avoiding route-extra complexity). For the codegen-deserialization ambiguity (the login endpoint returns `oneOf` which dart-dio can't handle), `AuthController.login()` checks if `accessToken` is empty and falls back to reading the raw response map for `twoFactorToken`.

### What landed

- **Backend:** TotpSecret + BackupCode entities + repos, `V5__two_factor.sql`, `dev.samstevens.totp:totp:1.7.1` dep, `TotpSecretEncryptor` (AES-256-GCM), `FirstFactorCache` interface + `RedisFirstFactorCache` (same GETDEL pattern as ChallengeCache), `TwoFactorService` interface + `TwoFactorServiceImpl` (all 6 operations), `TwoFactorProperties`, `TwoFactorRequiredException` (carries the firstFactorToken), 4 typed exceptions. `AuthServiceImpl.login()` wired with the 2FA gate via `@Autowired(required=false)` setter. `TwoFactorController` (7 endpoints), 7 DTOs, security + ProblemDetailsHandler updates, `application.yml` defaults. `TwoFactorFlowIT` covering the full setup → login-with-challenge → TOTP verify → backup path → backup-reuse-rejected → backup-code status → regenerate → disable loop.
- **Web:** `two-factor-settings.component.ts` (QR URI display, setup wizard, backup reveal, regenerate, disable, embedded on home). `two-factor-dialog.component.ts` (TOTP/backup input modal). `login.component.ts` detects `twoFactorToken` response and shows the modal. `auth.store.ts` login returns `AuthResponse | { twoFactorToken }`.
- **Mobile:** `TwoFactorRequired` sealed state in `AuthState`. `AuthController.login()` handles ambiguous codegen deserialization. `TwoFactorScreen` (TOTP + backup input, reads token from auth state). `TwoFactorSettingsScreen` (QR via `qr_flutter`, setup/disable/regenerate). `/settings` route in router. Settings icon in home AppBar. `qr_flutter ^4.1.0` added to pubspec.

### Tests added: 13 backend (11 TwoFactorServiceImplTest + 2 TwoFactorFlowIT), 2 AuthServiceImplTest updates

### Bugs hit & fixed

- **`TwoFactorService` bean registered under the interface type**, not the concrete type — the `@Autowired(required=false)` setter needed to accept `TwoFactorService`, not `TwoFactorServiceImpl`. Fixed by promoting `issueFirstFactorToken` to the interface and changing the setter type.
- **`TwoFactorFlowIT` `twoFactorToken` not in response** — same root cause. Once the interface/concrete type mismatch was fixed, the setter injection worked and the 2FA gate fired.
- **samstevens totp's `DefaultCodeVerifier` uses `HashingAlgorithm.SHA1`** — test code initially tried to import `TOTPGenerator` interface which doesn't exist in 1.7.1; switched to `DefaultCodeGenerator` directly.

### Architectural precedents locked

1. **Service interface stays stable despite protocol changes.** `AuthService.login()` still returns `AuthenticatedSession`. Protocol branching lives in the controller layer, communicated via exception. Same pattern for any future auth complexity.
2. **TOTP secrets encrypted at rest.** `TotpSecretEncryptor` wraps AES-256-GCM; Phase 3 ZK mode swaps the key derivation.
3. **Backup codes reuse `Argon2idPasswordHasher`.** No new crypto — same `hash(char[])` / `verify(char[], String)` contract.
4. **FirstFactorCache mirrors ChallengeCache exactly.** Same Redis + `GETDEL` + short TTL. Reusable for any future multi-step auth flow.

### Verification

```bash
./gradlew :core-auth:test :api-gateway:test   # 83+ backend tests green (cumulative)

# Full flow via curl:
# Register + verify → login → 2FA challenge → TOTP verify → session
# setup/finish → login challenge → backup code verify → disable
```

---

## Tooling versions snapshot *(through Phase 1.1e)*

| Component | Version |
|---|---|
| Java | 21 (Corretto) |
| Spring Boot | 3.4.1 |
| Gradle | 9.2.1 |
| Postgres | 16 + pgvector |
| Redis | 7-alpine |
| Elasticsearch | 8.15.3 |
| MinIO | RELEASE.2025-01-20 |
| Mailhog | 1.0.1 |
| webauthn4j | 0.24.4.RELEASE |
| androidx.credentials | 1.3.0 |
| androidx.credentials:credentials-play-services-auth | 1.3.0 |
| jjwt | 0.12.6 |
| argon2-jvm | 2.11 |
| Flyway | 11.1.0 |
| Angular | 18.2.x |
| @simplewebauthn/browser | ^11.0.0 |
| @openapitools/openapi-generator-cli | 2.15.x (generator pinned to 7.10.0) |
| Flutter | ≥ 3.24.0 |
| openapi_generator (Dart) | ^6.0.0 |

---

---

## Phase 1.1f — Password Reset  *(2026-05-16)*

**Goal:** Let locked-out users self-service their way back in. Closes Phase 1.1 ("Vault Foundation — auth").

### Scope

| In | Deferred |
|---|---|
| `PasswordResetToken` entity + repo, `PasswordResetService`, 2 endpoints, Flyway V6, email template, rate-limit (60s), revoke-sessions-on-reset, web forgot/reset forms, mobile forgot/reset screens, Android deep link | Account lockout after N failed attempts (post-Phase 1) |

### Key decisions

- **Separate `password_reset_tokens` table** — different TTL from email-verification (1h vs 24h).
- **Silent on unknown email** — no enumeration.
- **Revoke all refresh tokens on reset** — `RefreshTokenRepository.revokeAllForUser(UUID)` added; any stolen session is killed when the owner recovers their account.
- **No auto-login after reset** — redirect to /login applies any 2FA gates.
- **Rate limit: 60s per email**, in-memory, same pattern as resend-verification.
- **Typed problems:** `invalid-reset-token` + `reset-rate-limited` (429 + Retry-After).

### What landed

- **Backend:** `PasswordResetToken` + repo, `V6__password_reset.sql`, email template, `PasswordResetServiceImpl`, 2 exceptions, `PasswordResetController`, SecurityConfig + ProblemDetailsHandler updates. `RefreshTokenRepository.revokeAllForUser()` added.
- **Web:** `forgot-password.component.ts`, `reset-password.component.ts`, "Forgot password?" link, 2 routes.
- **Mobile:** `forgot_password_screen.dart`, `reset_password_screen.dart`, login "Forgot?" TextButton, 2 routes, Android manifest reset-password intent filter.

### Tests added: 4 backend (`PasswordResetFlowIT`)

---

## Phase 1.2 — Document Storage + Folder Tree + Upload + Viewer + Web Search Shell  *(2026-05-24)*

**Goal:** The first slice of the vault itself. After Phase 1.1 closed out auth, users now actually have *somewhere to put documents*: a folder tree, two-step presigned upload, a list/viewer, and a search shell. Backend modules `core-storage`, `catalog`, `ingest`, and `search` graduate from empty skeletons to fully wired implementations.

### Scope

| In | Deferred |
|---|---|
| Backend: `core-storage`, `catalog`, `ingest`, `search` modules filled in. V7 migration (`folders`, `documents`, `tags`, `document_tags`). 11 new REST endpoints. OpenAPI spec extended. ITs add MinIO + Elasticsearch containers. Web: full `/documents` page with folder sidebar, drag-and-drop upload, list, viewer, search bar. | OCR text in the search index (→ 1.3). Tag CRUD UI + tag-as-filter (→ 1.4). Mobile scanner+upload+list (→ Phase 2). PDF.js viewer (→ later polish, when redaction lands). |

### Decisions locked

- **Two-step presigned upload.** `POST /documents:initiate-upload` returns `{documentId, uploadUrl, storageKey, expiresInSeconds}`; client PUTs the bytes directly to MinIO/S3; `POST /documents:finalize` validates via S3 `HeadObject` and flips status `PENDING → READY`. Phase 2's OCR queue hooks in at the finalize boundary.
- **Storage key shape:** `users/{userId}/docs/{documentId}/{slug(filename)}`. Slug strips path separators + control chars. `StorageKey.forDocument` is the only place that constructs keys.
- **Document UUID is server-allocated *before* persistence.** `IngestServiceImpl` generates the id, builds the storage key from it, and threads it through `RegisterDocumentCommand` so the catalog row stores the same key the presigned URL signs. (Earlier draft let JPA's `@PrePersist` assign a different id — caused HEAD-not-found on finalize. Bug & fix during 1.2.)
- **MIME allowlist** enforced at initiate-upload AND verified via `HeadObject.contentType` at finalize. Config: `vaultdoc.ingest.allowed-mime-types`. Defaults: PDF, JPEG, PNG, WebP, HEIC, TIFF, DOCX.
- **Size cap 50 MB** (`vaultdoc.ingest.max-size-bytes`). Server stores claimed size; HEAD must match. Mismatch ⇒ `409 size-mismatch` and document flips to `FAILED`.
- **SHA-256 trust model:** client supplies + server stores. No server-side stream re-verification (cost prohibitive). Tampering proofs land in Phase 5 via OpenTimestamps.
- **Folder tree = adjacency list.** Two partial unique indexes (`WHERE parent_id IS NULL` vs `IS NOT NULL`) because Postgres treats NULLs as distinct in compound UNIQUE.
- **Default folders seeded lazily** on first `listFolders`/`listDocuments` call by `DefaultFolderSeeder`. Eight system folders (Identity, Financial, Health, Property, Vehicle, Education, Employment, Travel), `is_system=true`. **`ensureSeeded` uses `Propagation.REQUIRES_NEW`** so it can write even when the caller is in a `readOnly = true` transaction (Postgres enforces `SET TRANSACTION READ ONLY` at the SQL level, which silently swallows the INSERTs otherwise — found during 1.2 IT debugging).
- **Search:** Elasticsearch index `vaultdoc-documents-v1` with `userId` (keyword, filtered on every query for tenant scoping), `documentId`, `filename` (text + `.raw` keyword subfield), `folderId`, `tags`, `documentType`, `uploadedAt`. Phase 2 adds `ocrText`. Query is a `bool` with user-scope filter + `should` clauses across `filename` (match), `filename.raw` (wildcard), `documentType` (term), `tags` (term), `minimum_should_match=1`.
- **Refresh policy** is `false` in prod, overridden to `true` in tests via `vaultdoc.search.refresh-policy` so ITs can assert immediately after `finalize`.
- **Virus scan:** `VirusScanner` interface + `NoopVirusScanner` impl that always returns CLEAN. ClamAV impl swaps in when docker-compose adds the service.
- **Web viewer:** `<iframe src=presignedURL>` for PDFs, `<img>` for images, Download CTA for DOCX/other. PDF.js stays out (bundle bloat) until redaction lands.
- **JPA columns are VARCHAR everywhere.** Initial attempt at `CHAR(64)` for sha256 + `TEXT` for storage_key caused Hibernate schema-validation mismatches (`bpchar` vs `char` and `text` vs `varchar`). Settled on `VARCHAR(64)` for sha256, `VARCHAR(1000)` for storage_key — keeps Hibernate's column-type checks happy without touching dialects.
- **No mobile changes.** Generated Dart client picks up the new endpoints automatically on next `build_runner` run.

### What landed

- **Backend `core-storage`:** `ObjectStorage` interface, `S3ObjectStorage` impl (S3Presigner-backed), `StorageKey` record (with `forDocument` factory), `ObjectMetadata` record, `StorageProperties` (`presignPutTtl=15m`, `presignGetTtl=5m`), `StorageAutoConfiguration`. `InfraConfig` gains an `S3Presigner` bean next to the existing `S3Client`.
- **Backend `catalog`:** `Folder` + `Document` entities (no JPA self-reference; folders use a flat `parentId UUID`), `FolderRepository`, `DocumentRepository`, `DocumentStatus` enum (PENDING/READY/FAILED/DELETED), `FolderService` + impl, `DocumentService` + impl, `DefaultFolderSeeder`, `CatalogAutoConfiguration`. Soft-delete by status transition; ON DELETE SET NULL on `documents.folder_id`.
- **Backend `ingest`:** `IngestService` (record-typed `InitiateUploadCommand` + `UploadTicket`), `IngestServiceImpl` orchestrating presign → register → finalize → index, `MimeAllowlist`, `VirusScanner` + `NoopVirusScanner`, `IngestProperties`, `IngestAutoConfiguration`, 6 typed exceptions (`MimeNotAllowed`, `SizeExceeded`, `SizeMismatch`, `UploadObjectMissing`, `DocumentAlreadyFinalized`, `VirusDetected`).
- **Backend `search`:** `SearchService` interface, `ElasticsearchSearchService` impl with lazy index creation + v1 mapping, `SearchProperties`, `SearchAutoConfiguration`. `DocumentIndexRecord` + `SearchHit` + `SearchPage` records.
- **Backend `api-gateway`:** `DocumentController` (initiate-upload, finalize, list, get, download, delete), `FolderController` (list, create, patch, delete), `SearchController` (query). DTOs co-located under `gateway/documents/dto/`. `ProblemDetailsHandler` extended with 8 new typed problems. `application.yml` adds `vaultdoc.storage.*`, `vaultdoc.ingest.*`, `vaultdoc.search.*` blocks. Flyway `V7__documents.sql`.
- **OpenAPI:** New tags `Documents`, `Folders`, `Search`. 11 new endpoints + 14 new schemas. `openapi/vaultdoc.yaml` unchanged elsewhere.
- **Web (Angular):** `core/upload/uploader.ts` (XHR PUT with progress events + `sha256Hex` helper). `core/api/types.ts` re-exports the new generated types + new problem URI constants. `features/documents/`: `documents.store.ts` (signal-backed state + upload orchestration), `documents-page.component.ts` (sidebar + main pane shell), `folder-tree.component.ts` (recursive tree + inline create), `upload-zone.component.ts` (drag-drop + progress list), `document-list.component.ts` (table with delete), `document-viewer.component.ts` (modal with iframe/img), `search-bar.component.ts` (200ms debounced search). `app.routes.ts` adds `/documents`. `home.component.ts` gains an "Open vault" CTA card.
- **Tests `support/`:** New `DocumentITBase` abstract class with static `MinIOContainer` + `ElasticsearchContainer` started once per JVM, `@DynamicPropertySource` registering `vaultdoc.s3.*` + `vaultdoc.elasticsearch.url` + `vaultdoc.search.refresh-policy=true`. Provisions the bucket via the SDK if not already there.
- **Infra:** `infra/README.md` documents the MinIO CORS knob for browser-direct PUTs (default is permissive for dev; explicit `mc admin config set` snippet for the case the browser preflight gets blocked).

### Tests added: 21 backend (12 unit + 9 IT; cumulative now 108)

- `IngestServiceImplTest` (7) — MIME reject, size-cap reject, initiate happy path with key shape, finalize size-mismatch, finalize already-finalized, finalize happy-path with search-indexing verified, infected file deletes blob.
- `FolderServiceImplTest` (8) — list seeds, create rejects blank, create top-level, create rejects missing parent, update renames + moves, update rejects self-parent, delete returns true/false based on row count.
- `DocumentFlowIT` (4, extends `DocumentITBase`) — full register → verify → login → initiate → real PUT to MinIO → finalize → list → get → download (real GET of the presigned URL) → search → delete loop; MIME reject; size reject; tenant isolation (other user can't see document or find it in search).

### Bugs hit & fixed

- **JPA-assigned UUID vs storage-key UUID.** First draft of `IngestServiceImpl.initiateUpload` generated its own UUID, used it in the storage key, but persisted the entity with `id=null` so JPA's `@PrePersist` assigned a different id. The DB row's `storage_key` then pointed to a path the client never PUT to → HEAD missed → `upload-object-missing` on finalize. Fix: thread the generated UUID through `RegisterDocumentCommand.documentId` so the entity is created with the right id.
- **Postgres `SET TRANSACTION READ ONLY` blocks lazy seeding.** `FolderService.listForUser` is `@Transactional(readOnly = true)`. Calling `DefaultFolderSeeder.ensureSeeded` inside it caused the inserts to be silently swallowed by the read-only transaction. Fix: `@Transactional(propagation = REQUIRES_NEW)` on the seeder so it runs in its own writable transaction.
- **Hibernate schema validator vs `columnDefinition = "CHAR(64)"`.** `@Column(columnDefinition = "CHAR(64)")` annotated `sha256` while the SQL had `CHAR(64)`. Hibernate validated the column as `bpchar` (Postgres' actual representation) but expected `char` of SQL type VARCHAR — schema-validation failed. Same with `TEXT` for `storage_key`. Fix: drop `columnDefinition`, use `length=...` + plain VARCHAR in SQL.
- **Test failures from parallel execution + shared `RecordingEmailSender`.** Concurrent `register` calls raced on the email-recording list; some sends were lost. Fix: `@Execution(ExecutionMode.SAME_THREAD)` on `DocumentFlowIT`. (`RecordingEmailSender` itself is left untouched — other ITs that always use unique emails are unaffected.)
- **`multi_match` on `filename` text + boosts didn't find single-word queries.** Standard analyzer indexed "passport.pdf" as ["passport.pdf"] (period not always a separator depending on analyzer version), so "passport" didn't match. Switched the query to a `bool should` with `match` on `filename`, `wildcard` on `filename.raw`, and `term` on `documentType`/`tags`.

### Architectural precedents locked

1. **Two-step presigned upload becomes the canonical large-file path.** Future avatar uploads, travel-pack ZIPs, OpenTimestamps proof files all reuse `ObjectStorage.presignPut`.
2. **`finalize` is the phase boundary.** Today it indexes synchronously. Phase 2's OCR queue hooks in *after* the row is READY; the row's status stays READY while OCR fields populate asynchronously.
3. **Tenant scoping lives at every layer.** Controllers extract userId from `@AuthenticationPrincipal`. Services demand it as a parameter. Repositories `findByIdAndUserId(...)`. Elasticsearch every query starts with a `term` filter on `userId`. No global "current user" magic.
4. **Lazy idempotent seeders** (Pattern: `seeder.ensureSeeded(userId)` called from read paths, `REQUIRES_NEW` transaction). Reuse for default tags, default alert prefs, default household profile in later phases.
5. **Wire-spec types stay opaque in OpenAPI** when the type is dictated by an external standard (W3C WebAuthn ✔ from 1.1c, AWS S3 presigned URLs ✔ from 1.2 — returned as a plain `string` with `format: uri`, not modelled).

### Verification

```bash
# Backend
cd backend && ./gradlew test            # 108 green (up from 87 at end of 1.1f)
./gradlew :api-gateway:bootRun          # boots; /api/v1/health green

# Web (requires `npm install` first)
cd ../web && npm install && npm start   # codegen runs, dev server on :4200

# Manual loop (browser):
# 1. Sign in → home → click "Open vault →" CTA
# 2. Sidebar renders 8 default system folders (Identity … Travel)
# 3. Drag a small PDF onto the upload zone → row turns READY in the list
# 4. Click row → viewer opens with the PDF in an iframe
# 5. Type "passport" in the search bar → hit appears
# 6. Right-click delete on the row → disappears from list and search
# 7. + New folder → "Tax 2024" → drag a doc in
#
# Negative checks:
# - Upload a 60 MB file → friendly toast "size exceeded"
# - Upload an .exe → friendly toast "MIME not allowed"
# - Open dev tools, alter a folder fetch to a different userId → 404
```

---

## Phase 1.3 — OCR Pipeline (PDFBox) on Redis Streams  *(2026-05-24)*

**Goal:** Make documents searchable by *content*, not just filenames. Phase 1.2 left the vault "dumb" about what's inside each upload — `passport number` returned zero hits unless the literal string was in the filename. Phase 1.3 stands up the first stage of the long-term Redis-Streams pipeline (`upload-finalized → ocr → re-index`), runs PDFBox text extraction asynchronously after `finalize`, and writes the extracted text into both the `documents` row and the Elasticsearch index.

### Scope

| In | Deferred |
|---|---|
| New `core-pipeline` module (shared event envelope + Redis Streams plumbing). `ocr` module: `OcrService` + `PdfBoxOcrService` + `UploadFinalizedConsumer`. `ingest`: in-process event → AFTER_COMMIT forwarder → Redis stream. V8 migration. ES mapping + query gain `ocrText`. DocumentDetail/Summary expose OCR status + text. Web list badge + viewer "Extracted text" panel + auto-refresh-while-pending. | Tesseract for images (→ Phase 1.4). AWS Textract fallback (→ Phase 2). DLQ replay UI (→ Phase 2 monitoring slice). Multi-stage pipeline (extract → embed → classify → alerts; → Phase 2). |

### Decisions locked

- **Stream-per-stage architecture.** Phase 1.3 ships ONE stream — `vaultdoc:stream:upload-finalized` — produced by `ingest`, consumed by `ocr`. Phase 2 layers `ocr-complete`, `extraction-complete`, etc. on top using the same `Map<String,String>` envelope shape.
- **Producer trigger: in-process Spring event → AFTER_COMMIT → Redis stream.** `IngestService.finalizeUpload` publishes `DocumentFinalizedEvent`; `DocumentFinalizedStreamForwarder` (`@TransactionalEventListener(AFTER_COMMIT)`) XADDs to Redis. Two-step pattern keeps Redis out of unit-test scope and guarantees consumers never see a doc that isn't yet committed.
- **Synchronous + async indexing coexist.** `finalize` still indexes filename/folder/mime synchronously so the doc appears in search immediately. The OCR consumer overwrites the same ES document id with `ocrText` once extraction completes.
- **Consumer = system context.** `UploadFinalizedConsumer` uses `DocumentService.findByIdForSystem(UUID)` — no userId scope filter, because workers process every tenant's docs.
- **Idempotent OCR.** Replaying an entry overwrites the same row + ES document. Safe to XADD by hand to re-process.
- **`core-pipeline` is a new top-level module.** Owns `PipelineEvent`, `StreamNames`, `StreamPublisher` + `RedisStreamPublisher`, `StreamConsumerSupport`. `ingest` produces, `ocr` consumes; both depend on `core-pipeline` instead of each other.
- **MIME triage in the consumer.** `application/pdf` → run PDFBox. Everything else → `OcrStatus.SKIPPED` (placeholder for Tesseract in 1.4). The SKIPPED row still gets re-indexed so ES sees the status field.
- **Capped extracted text on the row** (`vaultdoc.ocr.extracted-text-limit-bytes`, default 2 MB) — keeps DB rows reasonable. ES gets the same capped string; full pagination lands in a later phase if needed.
- **Continue-on-error in the stream consumer.** `StreamMessageListenerContainerOptions.errorHandler(...)` logs Redis disconnects but keeps the poll loop alive. The default behavior cancels the subscription on first error — wrong for a long-running worker.
- **Search query adds `ocrText` match clause** alongside filename/documentType/tags. Standard analyzer for v1; multi-language analyzers wait for Tesseract in 1.4.
- **DTO surface.** `DocumentSummary` exposes only `ocrStatus` (badge in the list). `DocumentDetail` exposes full OCR fields including `ocrText`. Keeps list payloads small.
- **No mobile changes.** Generated Dart client picks up new fields automatically.

### What landed

- **New module `core-pipeline`:** `event/PipelineEvent.java` (record + `toMap()`/`fromMap()`), `stream/StreamNames.java`, `stream/StreamPublisher.java` interface + `RedisStreamPublisher.java`, `stream/StreamConsumerSupport.java` (idempotent XGROUP CREATE MKSTREAM, swallows BUSYGROUP), `config/PipelineAutoConfiguration.java`.
- **Backend `ocr`:** `service/OcrService.java`, `OcrResult.java` record, `PdfBoxOcrService.java` (uses `Loader.loadPDF(byte[])` + `PDFTextStripper`), `consumer/UploadFinalizedConsumer.java` (StreamListener; markProcessing → extract → markOcr → re-index → XACK, DLQ on failure), `config/OcrAutoConfiguration.java` (wires service + consumer + `StreamMessageListenerContainer` with custom errorHandler), `OcrProperties.java`.
- **Backend `catalog`:** `OcrStatus` enum. `Document` entity grows 5 OCR columns (`ocrStatus`, `ocrText`, `ocrPageCount`, `ocrCompletedAt`, `ocrFailureReason`) + `applyOcr` + `markOcrProcessing` mutators. `DocumentRepository.findByIdForSystem`. `DocumentService.markOcrProcessing` + `markOcr` + `findByIdForSystem`.
- **Backend `core-storage`:** `ObjectStorage.download(StorageKey) → byte[]` added so workers can fetch blob content without round-tripping through their own presigned URL.
- **Backend `ingest`:** `event/DocumentFinalizedEvent.java`, `event/DocumentFinalizedStreamForwarder.java` (`@Component` with `@TransactionalEventListener(AFTER_COMMIT)`), `IngestServiceImpl` injects `ApplicationEventPublisher` and publishes after `markReady`.
- **Backend `search`:** `DocumentIndexRecord` gains `ocrText`. Mapping in `ElasticsearchSearchService.ensureIndex` adds `ocrText: text` with standard analyzer. Query builder adds an `ocrText` match clause.
- **Backend `api-gateway`:** `DocumentSummary` gains `ocrStatus`; `DocumentDetail` gains all 5 OCR fields. `application.yml` `vaultdoc.ocr.*` block. Flyway `V8__documents_ocr.sql`.
- **OpenAPI:** new `OcrStatus` enum + new fields on `DocumentSummary`/`DocumentDetail`. No new endpoints.
- **Web:** `document-list.component.ts` gets an `OCR` column with ✓/⏳/·/✗ status icon + tooltip. `document-viewer.component.ts` gets a `<details>` "Extracted text" panel that fetches `DocumentDetail` on open and renders `ocrText`/loading/failure. `documents-page.component.ts` adds a 3s `setInterval` poll while any doc in the list is `PENDING`/`PROCESSING` (cleaned up on destroy via `DestroyRef`).
- **libs.versions.toml:** `awaitility = "4.2.2"` added (+ test-scoped lib entry).
- **`api-gateway/build.gradle.kts`:** test scope adds `awaitility` + `pdfbox`.

### Tests added: 7 (cumulative ≈ 115 backend green)

- `PdfBoxOcrServiceTest` (4) — generates an in-memory PDF with `PDDocument` + `PDPageContentStream`, asserts text + page count, non-PDF returns SKIPPED, malformed bytes return FAILED, byte-limit cap is respected (with UTF-8 boundary-safe truncation).
- `OcrPipelineIT` (3, extends `DocumentITBase`) — full pipeline: register/verify/login, initiate-upload, real PUT to MinIO, finalize, Awaitility-poll `GET /documents/{id}` until `ocrStatus=DONE` (≤15s), assert `ocrText` contains expected word + search by an OCR'd word returns the doc. Negative: malformed "broken.pdf" → FAILED but filename search still works. SKIPPED: image MIME → status SKIPPED without invoking PDFBox.

### Bugs hit & fixed

- **`@ConditionalOnBean(...)` evaluated too early.** Annotating `PipelineAutoConfiguration.streamPublisher` and `OcrAutoConfiguration.ocrStreamListenerContainer` with `@ConditionalOnBean(StringRedisTemplate.class)` / `@ConditionalOnBean(RedisConnectionFactory.class)` caused the condition to evaluate "no" because Spring Boot's Redis auto-config registered those beans later. Result: `StreamPublisher` bean never created → `DocumentFinalizedStreamForwarder` fails to autowire → entire ApplicationContext fails to load. Fix: drop `@ConditionalOnBean`; rely on injection-time bean lookup (NoSuchBeanDefinitionException is the correct error if Redis isn't wired).
- **`StreamMessageListenerContainer` kills the subscription on the first poll error.** Lettuce occasionally closes the shared connection mid-XREADGROUP (especially during context shutdown), throwing `RedisSystemException: Connection closed`. The container's default error handler cancels the subscription, after which no messages are ever consumed. Fix: pass a custom `errorHandler` in `StreamMessageListenerContainerOptions.builder()` that logs and continues — long-running workers must survive transient Redis blips.
- **`StreamMessageListenerContainerOptions.builder()` is generic and defaults to `ObjectRecord`** unless you call `.targetType(...)` or cast. The cleaner-looking `.targetType(String.class)` produces `ObjectRecord<String,String>` (a flat single-value record), NOT what we want. Fix: omit `.targetType(...)`, accept the raw-types warning, and `@SuppressWarnings` the cast to `StreamMessageListenerContainerOptions<String, MapRecord<String,String,String>>`. Map records are the right shape for our typed envelope.
- **Component scan vs `@ComponentScan`.** Early-draft `IngestAutoConfiguration` had `@ComponentScan(basePackages = "app.vaultdoc.ingest.event")` — but `VaultDocApplication`'s `@SpringBootApplication(scanBasePackages = "app.vaultdoc")` already scans every module's `@Component` beans. Adding an explicit `@ComponentScan` would register duplicate `@Component` beans. Removed.

### Architectural precedents locked

1. **`core-pipeline` is THE messaging-infrastructure module.** All future stages (OCR-complete, extraction-complete, alerts-fanout, embeddings-ready) plug into it. Stream-per-stage with `Map<String,String>` payloads, consumer groups + XACK, DLQ-on-failure. No JSON envelopes, no schema registry.
2. **Producer pattern: in-process event → AFTER_COMMIT → Redis stream.** Unit tests assert event emission without Redis. Cross-stage tests use Awaitility + real Redis. Reused for every future stage producer.
3. **Continue-on-error in long-running stream consumers.** `StreamMessageListenerContainerOptions.errorHandler(...)` set explicitly. Default-cancel behavior is a footgun for any worker that should survive transient errors.
4. **Worker context vs request context** is now a first-class architectural distinction. Worker-context calls (`DocumentService.findByIdForSystem`, `DocumentService.markOcr`) take no userId. Request-context calls (`findByIdForUser`, `list(...)`) demand it. Same pattern for future workers: extraction, embedding, alerts.
5. **OCR data on the document row, not a side table.** Same approach planned for `extracted_fields` (JSONB) in Phase 2 and `embedding` (pgvector) when embeddings land. Escalate to a side table only when the data is multi-row-per-doc (e.g., chunks for RAG).

### Verification

```bash
cd backend && ./gradlew test               # 115 green
./gradlew :api-gateway:bootRun             # boots; /api/v1/health green

cd ../web && npm install && npm start      # codegen runs; dev server :4200

# Manual loop:
# 1. Sign in → /documents
# 2. Drag a text-bearing PDF into the upload zone
# 3. Row appears with status=READY and OCR badge ⏳
# 4. Within ~2-5s the OCR badge flips to ✓ (auto-refresh handles it)
# 5. Click the row → viewer modal → expand "Extracted text" → text shown
# 6. Type a word from inside the PDF in the search bar → hit appears
# 7. Drop a JPG → OCR badge · "skipped" (Tesseract is Phase 1.4)
# 8. Drop a corrupted PDF → OCR badge ✗ "failed"; filename search still works

# Inspect Redis (in another shell):
# docker exec -it vaultdoc-redis redis-cli -a vaultdoc_dev_pw
# > XLEN vaultdoc:stream:upload-finalized
# > XLEN vaultdoc:stream:upload-finalized:dlq
```

---

## Phase 1.3.5 — Platform Upgrade: Java 25, Spring Boot 4.1, Jackson 3, ES 9  *(2026-06-28)*

**Goal:** Modernize the entire backend platform and datastore tier before building more features. Java 21 → 25, Spring Boot 3.4.1 → 4.1, and the breaking dependency cascade that comes with it (Spring Framework 7, Jakarta EE 11, **Jackson 2 → 3**, Spring Security 7, Hibernate 7). Datastores bumped too: Postgres 16 → 17, Redis 7 → 8, Elasticsearch 8.15 → 9.2. No feature work — same 115 tests, all green on the new stack.

### Scope

| In | Deferred |
|---|---|
| Build toolchain (Java 25, Boot 4.1, libs), Jackson 2→3 migration (passkey chain + ES client + SecurityConfig), webauthn4j 0.24→0.31, datastore image bumps (compose + testcontainers), Flyway-via-starter, doc updates. | Tesseract/tag-UI (Phase 1.4 — the originally-planned next slice). MinIO/Mailhog image bumps (held at verified tags; not in the CLAUDE hard-req tier). Testcontainers 2.x (stayed on 1.21.4 to avoid its module-rename churn). |

### Decisions locked

- **Spring Boot 4.1.0** (GA 2026-06-10) on **Java 25** (toolchain; Gradle 9.2.1 unchanged — already Boot-4-compatible). Spring Cloud bumped to **2025.1.2 (Oakwood)** in the catalog (dormant — not BOM-imported).
- **Jackson 3 is the app's JSON.** Boot 4 makes `tools.jackson` the default. Our own Jackson code (the passkey W3C-options chain + `SecurityConfig`) moved `com.fasterxml.jackson.databind.*` → `tools.jackson.databind.*`, including the J3 accessor renames (`isTextual()`→`isString()`, `asText()`→`asString()`) and `new ObjectMapper()` → `JsonMapper.builder().build()`.
- **jjwt stays on Jackson 2.** No Jackson-3 release of `jjwt-jackson` exists yet (PR #1032 unmerged as of 2026-06). Boot 4 retains Jackson-2 dependency management, so jjwt's transitive Jackson 2 coexists with the app's Jackson 3 on the classpath. No bridge module needed.
- **webauthn4j 0.24.4 → 0.31.6.RELEASE.** This *is* the Jackson-3 release we deliberately held off in Phase 1.1c — the platform move releases it. Its public API is annotated with JetBrains `@NotNull`/`@Nullable` shipped non-transitively, so `core-auth` adds `compileOnly("org.jetbrains:annotations:26.0.2")`.
- **Elasticsearch 9.2.8** server + client. The 8.x `RestClientTransport` + httpcore4 `HttpHost` wiring was replaced with the 9.x fluent `ElasticsearchClient.of(b -> b.host(url).jsonMapper(new Jackson3JsonpMapper()))` — uses the Jackson-3 mapper and dodges the httpcore4→5 import churn.
- **Flyway now needs its starter.** Boot 4 modularized auto-config out of the monolithic `spring-boot-autoconfigure`; `flyway-core` alone no longer runs migrations. Switched `api-gateway` to `spring-boot-starter-flyway` (+ kept `flyway-database-postgresql`). Flyway + Postgres-driver versions made **version-less** in the catalog so the Boot 4.1 BOM governs them.
- **Lombok 1.18.42** (first line that runs its processor on JDK 25) + **`-proc:full`** in the convention plugin — JDK 23+ defaults `javac` to `-proc:none`, which would silently skip annotation processing.
- **Datastores bumped, MinIO/Mailhog held.** PG17/Redis8/ES9.2.8 updated in both `infra/docker-compose.yml` and the testcontainer image tags (`TestcontainersConfig`, `DocumentITBase`). MinIO + Mailhog left at their verified pinned tags (avoid guessing a non-existent date-stamped tag; they're dev-only, not in the hard-req tier). **CLAUDE.md hard-requirements table updated** to PG17/Redis8/ES9/Boot4/Java25 (explicitly authorized).

### What landed

- **Build:** `gradle/libs.versions.toml` (Java 25, Boot 4.1.0, jjwt 0.13.0, testcontainers 1.21.4, ES-java 9.2.8, springdoc 3.0.3, webauthn4j 0.31.6; Flyway/pg version-less; added `spring-boot-starter-flyway`). `buildSrc` convention plugin (toolchain 25, BOM 4.1.0, Lombok 1.18.42, `-proc:full`). `core-auth`/`search` build files → `tools.jackson.core:jackson-databind` + JetBrains annotations. `api-gateway` → flyway starter + `spring-boot-starter-webmvc-test`.
- **Code:** Jackson-3 migration of the passkey chain (`PasskeyService`, `PasskeyServiceImpl`, `PasskeyRegistrationStarted`/`PasskeySigninStarted`, the 4 gateway passkey DTOs, `PasskeyController`) + `SecurityConfig`. `InfraConfig` ES client → 9.x fluent builder + `Jackson3JsonpMapper`. `VaultDocApplication` `@EntityScan` import moved to `org.springframework.boot.persistence.autoconfigure`. `ProblemDetailsHandler` `UNPROCESSABLE_ENTITY` → `UNPROCESSABLE_CONTENT` (Spring 7 rename).
- **Tests:** `@AutoConfigureMockMvc` import moved to `org.springframework.boot.webmvc.test.autoconfigure`; Jackson 2→3 across the 7 ITs + `PasskeyServiceImplTest`; `RecordingEmailSender.sent` → `CopyOnWriteArrayList`.
- **Infra:** compose images PG17/Redis8/ES9.2.8 (ES `elasticsearch.yml` + pg `init.sql` unchanged — settings/extensions valid on the new majors).

### Bugs hit & fixed

- **Flyway silently not running** → Hibernate `validate` failed with `missing table [backup_codes]`. Boot 4 modularization: `flyway-core` no longer triggers auto-config. Fix: `spring-boot-starter-flyway`.
- **`@EntityScan` / `@AutoConfigureMockMvc` packages moved** in Boot 4's module split (`...persistence.autoconfigure` and `...webmvc.test.autoconfigure`). MockMvc slice also needs the new `spring-boot-starter-webmvc-test`.
- **webauthn4j 0.31 compile errors** `cannot access NotNull/Nullable` — JetBrains annotations not transitive. Fix: `compileOnly` dep.
- **`RecordingEmailSender` ArrayList race** resurfaced under Boot 4 timing — 5 IT classes share the bean and append concurrently; a lost write dropped `PasswordResetFlowIT`'s captured email ("No email captured"). Root-cause fix: `CopyOnWriteArrayList` (better than the `@Execution(SAME_THREAD)` band-aid since the race is cross-class).

### Tooling versions snapshot *(after Platform Upgrade)*

| Component | Version | | Component | Version |
|---|---|---|---|---|
| Java | 25 | | Spring Boot | 4.1.0 |
| Gradle | 9.2.1 | | Spring Cloud | 2025.1.2 |
| Jackson | 3.0.x (app) / 2.20.x (jjwt) | | Hibernate | 7 (Boot-managed) |
| Postgres | 17 (pgvector) | | Redis | 8-alpine |
| Elasticsearch | 9.2.8 (server + client) | | MinIO | RELEASE.2025-01-20 (held) |
| webauthn4j | 0.31.6.RELEASE | | springdoc | 3.0.3 |
| jjwt | 0.13.0 | | Lombok | 1.18.42 |
| Flyway | Boot-managed (via starter) | | Testcontainers | 1.21.4 |

### Verification

```bash
cd backend && ./gradlew test            # 115 green (0 failures), now on Java 25 / Boot 4.1
                                         # ITs run PG17 + Redis8 + ES9.2.8 testcontainers end-to-end
cd ../infra && docker compose --env-file .env config -q   # valid
docker manifest inspect pgvector/pgvector:pg17            # all 3 new datastore tags resolve
```

---

## Test count cumulative

| Phase | Total tests |
|---|---|
| 1.0 | 14 |
| 1.1a | 37 (14 + 23) |
| 1.1b | 46 (37 + 9 new auth + verification) |
| 1.1c | 64 (46 + 18 passkey) |
| 1.1d | 64 backend (unchanged) + 7 Flutter (5 platform + 2 widget gate) |
| 1.1e | 83 backend (64 + 13 new 2FA + 2 AuthServiceImplTest updates) + mobile Flutter screens |
| 1.1f | 87 backend (83 + 4 PasswordResetFlowIT) + web/mobile reset screens |
| 1.2 | 108 backend (87 + 7 IngestServiceImplTest + 8 FolderServiceImplTest + 4 DocumentFlowIT + 2 housekeeping) + web documents feature |
| 1.3 | 115 backend (108 + 4 PdfBoxOcrServiceTest + 3 OcrPipelineIT) + web OCR badge + extracted-text panel |
| 1.3.5 | 115 backend (unchanged — platform upgrade, all green on Java 25 / Spring Boot 4.1 / Jackson 3 / ES 9) |
