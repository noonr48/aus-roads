// :feature:search — Search bar and results UI.
// Compose-based search overlay for the map screen.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.library.compose")
    id("ausroads.android.hilt")
}

android {
    namespace = "au.com.ausroads.feature.search"
    // Match :app's JVM test policy: Android Log witnesses are real on-device,
    // while the mockable android.jar must not kill coroutine error-path tests.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":offline:search"))
    implementation(project(":core:geo"))
    implementation(project(":core:model"))
    implementation(project(":ui:designsystem"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
