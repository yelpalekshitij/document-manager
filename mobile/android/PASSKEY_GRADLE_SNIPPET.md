# Gradle snippet for passkey support

After `flutter create --platforms=android --org app.vaultdoc --project-name vaultdoc_mobile .`
materialises `android/app/build.gradle.kts`, **add the lines below**.

## 1. Bump `compileSdk` and `targetSdk` if not already 35+

Inside the `android { }` block in `android/app/build.gradle.kts`:

```kotlin
android {
    namespace = "app.vaultdoc.vaultdoc_mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.vaultdoc.vaultdoc_mobile"
        minSdk = 26          // Credential Manager really wants 28+; UI gates older devices
        targetSdk = 35
        // … rest of defaultConfig unchanged
    }
    // … rest of android block unchanged
}
```

## 2. Add Credential Manager dependencies

At the bottom of `android/app/build.gradle.kts` (Flutter generates an empty
or near-empty `dependencies { }` block — add to it):

```kotlin
dependencies {
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
}
```

The `credentials-play-services-auth` artifact wires Google Password Manager
as a credential provider on devices that have Play Services. Without it,
passkeys still work via the OS-bundled Credential Manager, but the GPM
backup-sync UX is missing.

## 3. Sync

```bash
cd mobile
flutter pub get
```

If you're rebuilding inside Android Studio, Gradle Sync will pick up the
new deps. From the CLI:

```bash
flutter build apk --debug
```

— this triggers the Android Gradle build which fetches the deps and
compiles `PasskeyMethodChannel.kt`.

## Why these aren't pre-committed

Flutter's `android/app/build.gradle.kts` template depends on the Flutter SDK
version installed locally — the plugin block, namespace conventions, and
defaultConfig shape drift across releases. Committing a frozen copy here
would silently rot. The snippet above is intentionally minimal so it remains
easy to apply to whatever template `flutter create` emits in your environment.
