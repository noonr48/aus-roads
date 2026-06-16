// :data:tracks — Room store for recorded GPS tracks (breadcrumb points + GPX export).
// Pure persistence: no Compose. Hilt provides the repository.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.hilt")
}

android {
    namespace = "au.com.ausroads.data.tracks"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.hilt.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
