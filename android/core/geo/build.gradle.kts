// :core:geo — pure-Kotlin geographic + temporal math: coordinate formatting (DMS/UTM/MGRS),
// solar (sunrise/sunset/daylight), measurement (path length, polygon area), GPX 1.1 I/O,
// and fuel-range estimation. No Android dependencies, no Compose.

plugins {
    id("ausroads.kotlin.library")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.datetime)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
