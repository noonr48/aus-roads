// :core:common — pure-Kotlin utilities (Result types, dispatchers, time, logging).

plugins {
    id("ausroads.kotlin.library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
