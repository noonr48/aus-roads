// :traffic:provider-stub — no-op provider that always returns an empty feed.
// v0.1 ships with this provider only; v0.2 replaces it with :traffic:provider-sa.

plugins {
    id("ausroads.kotlin.library")
}

dependencies {
    api(project(":traffic:provider-api"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
