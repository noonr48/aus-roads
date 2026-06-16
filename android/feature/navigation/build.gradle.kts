plugins {
    id("ausroads.android.library")
    id("ausroads.android.library.compose")
    id("ausroads.android.hilt")
}

android {
    namespace = "au.com.ausroads.feature.navigation"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":routing:engine-api"))
    implementation(project(":traffic:congestion-api"))
    implementation(project(":ui:designsystem"))
    implementation(project(":navigation:tts"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.maplibre.android)
    // No play-services-location here: the fused-location impl of NavigationLocationSource
    // is provided per flavor by :app (withNetwork only), keeping gms out of the offline
    // build. This module stays gms-free.

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
