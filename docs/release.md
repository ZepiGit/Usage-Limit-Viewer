# Building and releasing

## Local Android builds

Use JDK 21 for tests (Robolectric API 36), or JDK 17 for packaging alone, and an
Android SDK with platform 36 and Build Tools 35 or later. Set
`ANDROID_HOME` or `sdk.dir` in the untracked `local.properties`. The Gradle wrapper
and dependency versions are committed; no global Gradle installation is needed.
The current build uses AGP 8.10.1 and Robolectric 4.16.1, with Java 17 app
bytecode. Use JDK 21 when running the build and tests together.

From the repository root in PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:assembleRelease
```

On Linux or macOS, use `./gradlew` with the same arguments. The debug APK is
`app/build/outputs/apk/debug/app-debug.apk`. Its application ID ends in `.debug`,
so it can be installed beside the release variant.

Tests include plain JVM tests, local HTTP and socket tests, and Robolectric tests
for Android storage and Compose screens. They do not require an emulator. The
HTML report is `app/build/reports/tests/testDebugUnitTest/index.html`; current
results and gaps are recorded in [verification.md](verification.md).

## Android signing

`app/build.gradle.kts` already reads the signing settings below. No source edit is
needed to sign a release.

| Environment variable | Gradle property or alternative environment variable |
|---|---|
| `ANDROID_KEYSTORE_PATH` | `USAGE_LIMITS_STORE_FILE` |
| `ANDROID_KEYSTORE_PASSWORD` | `USAGE_LIMITS_STORE_PASSWORD` |
| `ANDROID_KEY_ALIAS` | `USAGE_LIMITS_KEY_ALIAS` |
| `ANDROID_KEY_PASSWORD` | `USAGE_LIMITS_KEY_PASSWORD` |

For each setting, a nonblank `ANDROID_*` environment value takes precedence over
its `USAGE_LIMITS_*` Gradle property, followed by the `USAGE_LIMITS_*` environment
value. Local properties belong in the user's `.gradle/gradle.properties`, outside
the repository. Use an absolute keystore path.

- With all four settings absent, the release variant uses the debug key. This
  keeps local minified builds installable; it is for verification only.
- With any setting supplied, all four are required and the keystore must exist.
  An incomplete configuration fails instead of falling back to debug signing.
- With a complete configuration, Gradle uses that keystore. The build still has
  to verify that the password and alias are valid.

The release variant uses `com.usagelimits`, including when debug-signed. It cannot
update an installation signed with a different key. Keep signing-key creation,
backup, access and distribution credentials under the release owner's control.
Never commit credentials or a keystore, and do not enable Gradle's configuration
cache for a signed build.

With the release owner's settings supplied:

```powershell
.\gradlew.bat --no-configuration-cache :app:bundleRelease :app:assembleRelease
```

Outputs are `app/build/outputs/bundle/release/app-release.aab` and
`app/build/outputs/apk/release/app-release.apk`. Supply `ANDROID_VERSION_CODE` and
`ANDROID_VERSION_NAME` for a release; the local defaults are `1` and `0.1.0`.
Check the version code against the last distributed build before uploading.

## CI and release artifacts

The Android workflow runs the API 36 unit suite on JDK 21 and assembles both
debug and release variants on JDK 17. It uploads the debug APK. Without signing
settings, its release build uses the debug key too. Local API 36 packaging and
all 400 Android tests passed; CI results remain pending. Artifact sizes and
remaining runtime checks are tracked in [release-readiness.md](release-readiness.md).

The separate release workflow runs on `v*` tags or manual dispatch. Its Android
job requires `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` from the repository secret store.
It decodes the key into the runner's temporary directory, builds with
`--no-configuration-cache`, and removes the key in an `always()` cleanup step.
It does not use the Gradle caching action. The uploaded artifacts include the
signed AAB, APK and matching R8 `mapping.txt`.

The workflow uses its run number as the Android version code and the manual
version input or tag name as the version name. Confirm those values before
starting a release. A configured workflow is not evidence that a signed build
has completed; the current audit did not use release credentials.

The iOS release job generates the Xcode project from
`ios/UsageLimits/project.yml` and creates an **unsigned** release archive on
macOS. Distribution signing, provisioning and store submission remain separate
release-owner steps. Neither workflow publishes to a store.

## Minified-build smoke test

The Android release variant enables R8 minification and resource shrinking.
`app/proguard-rules.pro` contains rules for generated serialization code and
readable crash locations; dependencies also contribute consumer rules. Keep
`mapping.txt` with the exact artifact it describes.

Build success does not establish that the minified app works on a device. Install
the local verification APK on an emulator or test phone:

```powershell
adb install -r app/build/outputs/apk/release/app-release.apk
```

Check launch, all four tabs and both widgets first. Then have the account holder
run the login and usage checks in [verification.md](verification.md). Record the
artifact, device, OS version and result. Cold-start time and memory use require
runtime measurements; APK size is not a substitute.

## Before public distribution

The five integrations are Codex, Claude, Antigravity, Grok and Kimi Code. Vendor
permission and terms review remain open, including the use of first-party OAuth
registrations and usage endpoints. A working response does not resolve that
review. Kimi also needs confirmation that the `UsageLimits` identity is allowed
on its coding API; the pasted-key route does not establish that approval.

Both platforms use the quota-cell and return-arrow mark in terracotta and cream. Android includes
an adaptive icon and a monochrome variant for themed launchers; iOS builds the
`AppIcon` asset catalog. `tools/generate_app_icons.py` regenerates its opaque PNGs
with Pillow, a development tool that is not included in either app.

Release-key custody, live account approval, legal and store copy, and publication
are human responsibilities outside this audit. See
[provider-auth-research.md](provider-auth-research.md) and the per-provider notes
for the implementation background. Treat builds as private verification
artifacts until those decisions and the device runbook are complete.
