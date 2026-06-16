// :offline:pack-api — Map pack contract (manifest schema, version negotiation, eviction).
// Pure Kotlin; consumed by the in-app downloader and (eventually) by the map-pack pipeline.

plugins {
    id("ausroads.kotlin.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
