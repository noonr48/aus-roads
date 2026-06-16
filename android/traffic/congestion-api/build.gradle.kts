plugins {
    id("ausroads.android.library")
}

android {
    namespace = "au.com.ausroads.traffic.congestion"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
