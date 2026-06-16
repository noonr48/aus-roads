// :traffic:provider-vic — VicRoads (AU-VIC) LiveTrafficProvider implementation.
// Polls the public ArcGIS MapServer for road incidents, roadworks, and closures.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "au.com.ausroads.traffic.provider.vic"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":data:settings"))
    implementation(project(":traffic:provider-api"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.hilt.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
