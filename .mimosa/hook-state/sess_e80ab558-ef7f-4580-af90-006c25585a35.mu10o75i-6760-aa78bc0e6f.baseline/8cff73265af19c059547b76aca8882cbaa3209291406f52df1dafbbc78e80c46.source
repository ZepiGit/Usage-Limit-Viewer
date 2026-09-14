plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

// Environment names match the release workflow; user-level Gradle properties are supported too.
fun signingValue(environment: String, property: String): String? =
    providers.environmentVariable(environment).orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(property).orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(property).orNull?.takeIf { it.isNotBlank() }

val releaseStorePath = signingValue("ANDROID_KEYSTORE_PATH", "USAGE_LIMITS_STORE_FILE")
val releaseStorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "USAGE_LIMITS_STORE_PASSWORD")
val releaseKeyAlias = signingValue("ANDROID_KEY_ALIAS", "USAGE_LIMITS_KEY_ALIAS")
val releaseKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "USAGE_LIMITS_KEY_PASSWORD")
val signingValues = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)

/** Commits reachable from HEAD, or 1 where there is no git to ask. See `versionCode`. */
val commitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(1).coerceAtLeast(1)
val releaseSigningConfigured = signingValues.any { it != null }
if (releaseSigningConfigured) {
    require(signingValues.all { it != null }) { "Release signing requires all four signing settings." }
    require(file(releaseStorePath!!).isFile) { "The configured release keystore file does not exist." }
}

android {
    namespace = "com.usagelimits"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.usagelimits"
        minSdk = 26
        targetSdk = 36
        // Supplied by the workflows; otherwise the commit count, which rises with the history
        // on every machine that builds the same commit. A hardcoded 1 made every build a
        // sideways install — fine for `adb install -r`, but an installer that sees the same
        // version code from a different run cannot tell an update from a re-install — and a
        // published release can never go back to a lower code.
        versionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: commitCount
        versionName = System.getenv("ANDROID_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // One debug key for every machine and every CI run, checked in at app/debug.keystore.
        //
        // Android refuses to update an installed app with an APK signed by a different key, and
        // the only way past that refusal is to uninstall — which deletes the database, the
        // credentials and every widget's configuration. Gradle's default debug key lives in
        // ~/.android and is minted fresh on any machine that lacks one, so a GitHub runner
        // signed each verification APK with a key nobody had seen before, and installing the
        // next one meant starting from nothing. A key that is repository content is the same
        // key everywhere. It is a DEBUG key: the password is Android's own default and nothing
        // distributed is signed with it; releases use the real keystore below.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Local minified builds stay installable. A debug-signed APK is for verification
            // only; the release workflow requires its real signing settings before building.
            signingConfig = if (releaseSigningConfigured) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Robolectric's native font zip filesystem is process-wide on Windows, while
// Android sandboxes are not. A fresh worker avoids sharing that native state.
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").startsWith("Windows")) forkEvery = 1
}

dependencies {
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // The CORE icon set, not the extended one. Seven icons are used in the whole app; the
    // extended artifact carries several thousand and was the largest single dependency in the
    // debug build — the one a tester installs. The three glyphs core lacks are drawn locally
    // in `AppIcons.kt` from their Material path data.
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material3.window.size)
    implementation(libs.androidx.window)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.turbine)
    // Compose UI tests, run on the JVM under Robolectric. This environment has no KVM, so an
    // emulator is not available; Robolectric is what makes the screens testable at all, and an
    // app whose screens are never rendered anywhere is an app nobody has run.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
