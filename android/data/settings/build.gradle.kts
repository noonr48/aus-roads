// :data:settings — DataStore-backed user preferences (theme, attribution overlay, traffic).
// Pure persistence; no UI.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.hilt")
}

android {
    namespace = "au.com.ausroads.data.settings"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
