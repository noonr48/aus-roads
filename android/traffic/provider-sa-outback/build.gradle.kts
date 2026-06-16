// :traffic:provider-sa-outback — DIT outback road conditions (AU-SA-OUTBACK).
// Fetches from maps.sa.gov.au ArcGIS MapServer for road closures and warnings.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "au.com.ausroads.traffic.provider.sa.outback"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
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
}
