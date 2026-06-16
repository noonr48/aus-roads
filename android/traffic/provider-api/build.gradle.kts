// :traffic:provider-api — LiveTrafficProvider interface + supporting types.
// Pure Kotlin, used by every traffic provider implementation.
// Architecture pivot: every new state (AU-NSW, AU-VIC, AU-national, global) is a new
// :traffic:provider-xx module that implements this interface.

plugins {
    id("ausroads.kotlin.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
