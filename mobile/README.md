# VaultDoc Mobile

Flutter mobile client. **Android-first** for Phase 1.0 through Phase 6; iOS comes online in Phase 7.

## One-time setup

The `lib/` source, `pubspec.yaml`, and `test/` are committed. The Android platform stubs (Gradle scripts, manifest, Kotlin host code, signing config) are **not** committed — Flutter generates them. Run:

```bash
cd mobile
flutter create --platforms=android --org app.vaultdoc --project-name vaultdoc_mobile .
```

Then materialise the OpenAPI client (see below), then `flutter pub get`.

## OpenAPI client generation

The Dart API client is generated from `../openapi/vaultdoc.yaml`. The generated package lives at `mobile/api/` (gitignored) and is consumed via a path dependency declared in `pubspec.yaml`.

```bash
# First time, or whenever the spec changes:
dart run build_runner build --delete-conflicting-outputs
flutter pub get
```

Both `lib/core/api/openapi_config.dart` (the `@Openapi(...)`-annotated marker class) and `pubspec.yaml` (`vaultdoc_api: path: api`) need to be present.

Never call HTTP endpoints by hand — go through the generated `gen.AuthApi` / `gen.SystemApi` exposed by `lib/core/api/api_client.dart`.

## Dev loop

```bash
flutter run -d <android-device-or-emulator>
```

The app expects the backend at `http://10.0.2.2:8080` (Android emulator's alias for the host). Override on a real device:

```bash
flutter run --dart-define=API_BASE_URL=http://192.168.1.10:8080
```

## Deep links (email verification)

The Flutter app handles two link shapes that both route to the `VerifyEmailScreen`:

1. **App Link**: `https://app.vaultdoc.app/verify?token=…` — production. Requires a `.well-known/assetlinks.json` on `app.vaultdoc.app`; not configured yet.
2. **Custom scheme**: `vaultdoc://verify?token=…` — works in dev today.

Add the intent filter once you've run `flutter create`. In `android/app/src/main/AndroidManifest.xml`, inside `<activity android:name=".MainActivity" …>`, add:

```xml
<!-- Custom scheme for local dev -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="vaultdoc" android:host="verify" />
</intent-filter>

<!-- App Link for production -->
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="app.vaultdoc.app" android:pathPrefix="/verify" />
</intent-filter>
```

### Test the deep link in dev

With the emulator running and Mailhog inbox open at `http://localhost:8025`, copy the token from the verification email body and:

```bash
adb shell am start -W -a android.intent.action.VIEW -d "vaultdoc://verify?token=<paste>"
```

The app should activate the account and land on the home screen.

## Phase 1.1b scope

What this milestone delivers: email-verification flow on top of the password auth from 1.1a, plus the OpenAPI codegen pipeline that replaces hand-written API classes. Document scanner, ML Kit, FCM, biometric unlock — later milestones (see CLAUDE.md Phase 1.2+ and Phase 3).

## Android specifics (when `flutter create` has run)

In `android/app/build.gradle.kts`, confirm:

- `minSdk = 26`
- `compileSdk = 35`
- `targetSdk = 35`

In `android/app/src/main/AndroidManifest.xml`, alongside the deep-link intent filters above:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
<uses-permission android:name="android.permission.CAMERA" />
```

The camera permission lights up in Phase 1.2 when the document scanner ships.

---

## Passkeys (Phase 1.1d)

Mobile passkeys go through Android's `androidx.credentials.CredentialManager` via a Flutter platform channel we own (`app.vaultdoc/passkey`). The Kotlin side lives at `android/app/src/main/kotlin/app/vaultdoc/vaultdoc_mobile/PasskeyMethodChannel.kt`; the Dart side at `lib/core/passkey/`.

### One-time setup after `flutter create`

1. Run `flutter create --platforms=android --org app.vaultdoc --project-name vaultdoc_mobile .` (idempotent).
2. Patch `android/app/build.gradle.kts` per `android/PASSKEY_GRADLE_SNIPPET.md` to add the Credential Manager deps.
3. The `AndroidManifest.xml` we ship already includes the `<queries>` block — Flutter's `create` step won't overwrite our existing manifest.
4. `flutter pub get && dart run build_runner build --delete-conflicting-outputs`.

### Capability gating

Passkeys require Android API 28+ (Android 9 Pie). On API 26–27 the UI hides the passkey CTAs; password sign-in continues to work. The check is `Build.VERSION.SDK_INT >= P` on the Kotlin side, exposed through `MethodChannel.invokeMethod('isSupported')`.

### Manual test path (Android emulator)

```bash
# Emulator needs a screen lock configured first — Credential Manager refuses
# to register a passkey on a device without lock-screen security.
flutter run -d <emulator-with-api-28+>

# 1. Sign in with password. Home screen → Passkeys panel → Add → enter a name.
# 2. Bottom sheet: tap "Create passkey". The new credential appears in the list.
# 3. Sign out. On the login screen, "Sign in with a passkey" is visible.
# 4. Tap it. Bottom sheet asks which credential. Pick yours. Land on home.
```

To trigger a passkey from `adb`:

```bash
# Bring the app to the login screen and trigger:
adb shell input tap <x> <y>     # tap "Sign in with a passkey"
```

### rpId on Android — dev caveat

`rpId=localhost` works in browsers (special-cased) but **not** on Android Credential Manager. For dev you either:

- **Bind the backend to a real domain via your hosts file** (e.g. `127.0.0.1 app.vaultdoc.local`) and override both `WEB_BASE_URL=https://app.vaultdoc.local:4200` and `PASSKEY_RP_ID=app.vaultdoc.local` for the backend. Self-signed TLS cert required.
- **Use ngrok / Tailscale Funnel** to expose `localhost:8080` over HTTPS with a real public hostname, then set `PASSKEY_RP_ID=<that hostname>`. Pair with `--dart-define=API_BASE_URL=<that hostname>`.

For day-to-day mobile work, the practical recommendation is hosts-file + self-signed cert. Document the dev rpId you settle on in `.env` so the rest of the team matches.

### Production checklist — Digital Asset Links

Production passkeys require a `assetlinks.json` file served from the rpId's apex:

```
https://app.vaultdoc.app/.well-known/assetlinks.json
```

Contents:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls",
               "delegate_permission/common.get_login_creds"],
  "target": {
    "namespace": "android_app",
    "package_name": "app.vaultdoc.vaultdoc_mobile",
    "sha256_cert_fingerprints": [
      "<APK signing-cert SHA-256 fingerprint>"
    ]
  }
}]
```

To get the fingerprint of a release-signed APK:

```bash
keytool -list -v -keystore <path-to-release-keystore.jks> -alias <alias> \
  | grep "SHA256:"
```

For Play Store-signed APKs, fetch from the Play Console under "Setup → App integrity → App signing key certificate".

Without this file in place, the Android Credential Manager will reject the rpId with "no provider for site" — passkeys silently fail.

