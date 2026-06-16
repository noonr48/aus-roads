// :routing:engine-api — Routing engine abstraction.
// v0.1 ships API only; the Valhalla-backed implementation lives in v0.4 (closure-aware
// routing). The interface is locked now so that future providers plug in cleanly.

plugins {
    id("ausroads.kotlin.library")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
