plugins {
    id("ausroads.android.library")
    id("ausroads.android.hilt")
}

android {
    namespace = "au.com.ausroads.navigation.tts"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":routing:engine-api"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
