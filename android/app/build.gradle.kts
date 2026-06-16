// :app — the aus-roads Android application.
// v0.1 is offline-only: no INTERNET permission, no location, no Google Play Services.

import com.android.build.api.variant.BuildConfigField
import java.util.Properties

plugins {
    id("ausroads.android.application")
    id("ausroads.android.application.compose")
    id("ausroads.android.hilt")
}

// --- Release signing configuration -------------------------------------------
//
// For local development: leave signing.properties absent — the release variant
// will be unsigned and the build will still succeed. assembleRelease + apksigner
// can then be used to sign manually.
//
// For Play Store uploads: copy android/signing.properties.example to
// android/signing.properties (or ~/.gradle/signing.properties), fill in the real
// values, and the release variant will be signed automatically.

val signingProps = Properties().apply {
    val signingFile = rootProject.file("signing.properties")
    val userHomeFile = file("${System.getProperty("user.home")}/.gradle/signing.properties")
    val source = when {
        signingFile.exists() -> signingFile
        userHomeFile.exists() -> userHomeFile
        else -> null
    }
    if (source != null) {
        load(source.inputStream())
    }
}

android {
    namespace = "au.com.ausroads"
    defaultConfig {
        applicationId = "au.com.ausroads"
        versionCode = 1
        versionName = "0.1.0"
        // Default runner uses the real AusRoadsApp (@HiltAndroidApp), so
        // instrumented tests exercise the production Hilt graph + HiltWorkerFactory.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // The release signing config is only populated when signing.properties
        // is present. If the file is absent, the release build will succeed
        // but the APK will be unsigned — fine for local CI smoke tests.
        if (signingProps.isNotEmpty()) {
            create("release") {
                val storeFilePath = signingProps.getProperty("storeFile")
                if (storeFilePath != null) {
                    storeFile = file(storeFilePath)
                }
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        // BuildConfig is the single source of truth for the map-pack download
        // endpoint + whether this build is allowed to download at all.
        buildConfig = true
    }

    flavorDimensions += "network"
    productFlavors {
        create("offline") {
            dimension = "network"
            // No INTERNET. Preserves v0.1 privacy posture for sideload builds.
            // No download endpoint and downloads disabled — the offline flagship
            // cannot reach the network, so it must never try.
            buildConfigField("String", "MAP_PACK_BASE_URL", "\"\"")
            buildConfigField("Boolean", "CAN_DOWNLOAD_PACKS", "false")
        }
        create("withNetwork") {
            dimension = "network"
            // INTERNET declared in flavor-specific manifest source set.
            // Base URL defaults to the production CDN (release); the debug build
            // overrides it to the emulator host below (see androidComponents).
            buildConfigField("String", "MAP_PACK_BASE_URL", "\"https://cdn.aus-roads.example\"")
            buildConfigField("Boolean", "CAN_DOWNLOAD_PACKS", "true")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/DEPENDENCIES",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = false
    }

    testOptions {
        // android.util.Log (and other android.* stubs) return defaults instead of
        // throwing "not mocked" in JVM unit tests, so ViewModels that log inside
        // catch blocks (e.g. RouteViewModel, NearbyViewModel) stay unit-testable.
        unitTests.isReturnDefaultValues = true
    }

    // Single ABI for v0.1: arm64-v8a only.
    // MapLibre Native Android SDK is ~10-20 MB per ABI; shipping all four would
    // bloat the APK unnecessarily. arm64-v8a covers >99% of in-market devices in
    // 2026 and Play Store will require 64-bit by 2026 anyway.
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
}

// Per-variant override: only the withNetwork DEBUG build points at the local demo
// server. This app's native libs are arm64-only, so it runs on a physical device
// (not an x86_64 emulator) — testing uses `adb reverse tcp:8080 tcp:8080`, which maps
// the device's 127.0.0.1:8080 to the host's pack server (tools/map-pack-builder/scripts/serve-pack.sh).
// The flavor default (CDN) stays for withNetwork release; offline keeps its empty URL on
// both build types so a flavor-wide buildType override can't accidentally re-enable it.
androidComponents {
    onVariants(selector().withFlavor("network" to "withNetwork").withBuildType("debug")) { variant ->
        variant.buildConfigFields.put(
            "MAP_PACK_BASE_URL",
            BuildConfigField("String", "\"http://127.0.0.1:8080\"", "Local demo map-pack server (debug, via adb reverse)"),
        )
    }
}

dependencies {
    // Internal modules
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":traffic:provider-api"))
    implementation(project(":traffic:provider-stub"))
    implementation(project(":traffic:provider-sa"))
    implementation(project(":traffic:provider-sa-outback"))
    implementation(project(":traffic:provider-vic"))
    implementation(project(":traffic:provider-nsw"))
    implementation(project(":routing:engine-api"))
    implementation(project(":routing:engine-valhalla"))
    implementation(project(":offline:pack-api"))
    implementation(project(":offline:search"))
    implementation(project(":offline:pack-downloader"))
    implementation(project(":feature:search"))
    implementation(project(":feature:traffic"))
    implementation(project(":feature:navigation"))
    implementation(project(":traffic:congestion-api"))
    implementation(project(":navigation:tts"))
    implementation(project(":ui:designsystem"))
    implementation(project(":data:pins"))
    implementation(project(":data:settings"))
    implementation(project(":data:pack"))
    implementation(project(":data:routes"))
    implementation(project(":data:tracks"))

    // Feature build-out (P1 cores — see docs/roadmap/). The pure logic + stores are
    // wired into the app graph here; UI screens/services land in the device phase.
    implementation(project(":core:geo"))
    implementation(project(":feature:trip"))

    // Google Play Services (location for the "My Location" button) — confined to the
    // withNetwork flavor so the privacy-first `offline` flavor links no Play Services
    // and contains no location code (see src/{withNetwork,offline}/.../map/LocationProviders.kt).
    "withNetworkImplementation"("com.google.android.gms:play-services-location:21.3.0")

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    // MapLibre Native Android SDK — wired in v0.1.1 once the MapScreen is
    // implemented (see docs/adr/0007-maplibre-android-integration.md).
    implementation(libs.maplibre.android)

    // Glance (widgets)
    implementation(libs.glance.appwidget)

    // Test
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.work.runtime.ktx)
}
