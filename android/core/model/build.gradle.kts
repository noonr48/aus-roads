// :core:model — pure-Kotlin domain types shared across all modules.
// No Android dependencies, no Android types, no Compose.

plugins {
    id("ausroads.kotlin.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
