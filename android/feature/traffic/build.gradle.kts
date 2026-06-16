// :feature:traffic — Traffic overlay UI.
// Renders traffic events on the MapLibre map as colored markers and lines.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.library.compose")
    id("ausroads.android.hilt")
}

android {
    namespace = "au.com.ausroads.feature.traffic"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":traffic:provider-api"))
    implementation(project(":ui:designsystem"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.maplibre.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
