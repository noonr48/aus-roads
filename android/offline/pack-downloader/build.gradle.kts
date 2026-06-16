// :offline:pack-downloader — Map pack download orchestrator.
// Manages manifest fetching, resumable downloads, SHA-256 verification,
// and file-system state (current.json, previous.json).

plugins {
    id("ausroads.android.library")
    id("ausroads.android.hilt")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

android {
    namespace = "au.com.ausroads.offline.downloader"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":offline:pack-api"))
    implementation(project(":data:pack"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.hilt.android)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    // Ktor HTTP client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    // WorkManager + Hilt worker injection
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.mock)
}
