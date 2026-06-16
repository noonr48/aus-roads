// :ui:designsystem — shared Compose theme, typography, common Composables.
// No business logic, no domain types.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.library.compose")
}

android {
    namespace = "au.com.ausroads.ui.designsystem"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
