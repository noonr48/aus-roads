// :feature:trip — trip tools: over-speed alert, trip computer, proximity alerts,
// offline trip-share / overdue check-in, track recorder, and fuel/servo planner.
// Holds the pure logic + ViewModels; UI screens are wired in :app.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.hilt")
}

android {
    namespace = "au.com.ausroads.feature.trip"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:geo"))
    implementation(project(":data:tracks"))
    implementation(project(":routing:engine-api"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.hilt.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
