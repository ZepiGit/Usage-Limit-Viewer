# Building and releasing

## Debug builds

```bash
export ANDROID_HOME=/path/to/android-sdk    # must contain platform 35 + build-tools 35
./gradlew :app:assembleDebug
```

Produces `app/build/outputs/apk/debug/app-debug.apk`, signed with the standard
Android debug key. The debug build sets `applicationIdSuffix = ".debug"`, so it
installs alongside a release build rather than replacing it.

Requires JDK 17. The project targets Java 17 bytecode and Kotlin 2.0.21 with the
Compose compiler plugin.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

109 unit tests, all on plain JVM — no emulator, no Robolectric in the hot path.
That is deliberate: the parsers are the part most exposed to upstream change, so
they are kept free of `android.*` APIs and can be tested at full speed. Results
land in `app/build/reports/tests/testDebugUnitTest/index.html`.

## Release builds

```bash
./gradlew :app:assembleRelease
```

**The release build is currently signed with the debug key.** This is stated in a
comment in `app/build.gradle.kts` and is there so `assembleRelease` stays runnable
for local verification of minification and shrinking. It is **not** a distributable
artifact — an APK signed with the debug key cannot be updated by a real release
later, because the signing identity would change.

### Signing a real release

1. Create a keystore, once, and keep it somewhere it cannot be lost — losing it
   means never being able to update the app under the same application ID:

   ```bash
   keytool -genkeypair -v \
     -keystore usage-limits-release.jks \
     -keyalg RSA -keysize 4096 -validity 10000 \
     -alias usage-limits
   ```

2. Keep the credentials out of the repository. Put them in
   `~/.gradle/gradle.properties` (user-level, never committed), or supply them
   from the CI secret store as environment variables:

   ```properties
   USAGE_LIMITS_STORE_FILE=/absolute/path/usage-limits-release.jks
   USAGE_LIMITS_STORE_PASSWORD=…
   USAGE_LIMITS_KEY_ALIAS=usage-limits
   USAGE_LIMITS_KEY_PASSWORD=…
   ```

3. Replace the debug-signing line in `app/build.gradle.kts` with a real config
   that reads those properties and degrades gracefully when they are absent, so a
   contributor without the keystore can still build:

   ```kotlin
   signingConfigs {
       create("release") {
           val storePath = providers.gradleProperty("USAGE_LIMITS_STORE_FILE").orNull
           if (storePath != null) {
               storeFile = file(storePath)
               storePassword = providers.gradleProperty("USAGE_LIMITS_STORE_PASSWORD").orNull
               keyAlias = providers.gradleProperty("USAGE_LIMITS_KEY_ALIAS").orNull
               keyPassword = providers.gradleProperty("USAGE_LIMITS_KEY_PASSWORD").orNull
           }
       }
   }
   ```

   and in `buildTypes.release`, select it only when it is configured:

   ```kotlin
   signingConfig = signingConfigs.findByName("release")
       ?.takeIf { it.storeFile != null }
   ```

4. Never commit the keystore, the passwords, or a `local.properties` containing
   them. `.gitignore` already excludes `local.properties`.

## Minification

The release build enables R8 (`isMinifyEnabled` and `isShrinkResources`).
`app/proguard-rules.pro` is currently empty, which is correct for the present
dependency set: Room, Glance, WorkManager and Compose all ship their own consumer
rules, and `kotlinx.serialization` is driven by the compiler plugin rather than
reflection over class names.

If reflection-based serialization is ever introduced, that stops being true and
keep rules become necessary. **Always smoke-test a minified build before shipping
it** — R8 problems appear at runtime, not at build time:

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Exercise at least one login, one usage refresh and one widget placement.

## Before any public distribution

The provider integrations need a review that is legal and product, not technical.
`docs/provider-auth-research.md` and the per-provider documents set out the facts;
the open questions are:

- Several usage endpoints are internal endpoints of first-party CLI and desktop
  clients, not public APIs. Whether a third-party client may call them is a
  question for each vendor's terms, and it is not answered by the fact that they
  respond.
- The OAuth client identifiers are those first-party clients' public identifiers.
  Using them means presenting as that client.
- The app is built for accounts the person running it controls. That assumption
  should be stated in any store listing.

Until those are resolved, treat builds as private and personal.
