// :routing:engine-valhalla — Valhalla-backed RoutingEngine implementation.
// Uses the Rallista valhalla-mobile JNI library for offline routing.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.hilt")
}

android {
    namespace = "au.com.ausroads.routing.engine.valhalla"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":routing:engine-api"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.hilt.android)

    // Valhalla JNI — 0.3.1 is the first 16KB-page-aligned build (rebuilt on NDK r29).
    // Pinned to 0.3.1: it keeps Kotlin 2.0.20 metadata (compatible with the app's 2.0.21);
    // 0.4.0+ is what bumps the requirement to Kotlin 2.3. valhalla-models stay at 0.0.9.
    implementation("io.github.rallista:valhalla-mobile:0.3.1")
    implementation("io.github.rallista:valhalla-models:0.0.9")
    implementation("io.github.rallista:valhalla-models-config:0.0.9")

    // Moshi — required by ValhallaConfigManager (transitive from valhalla-mobile, but needed at compile time)
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
