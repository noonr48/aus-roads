// :data:routes — Room database for route history.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.hilt")
}

android {
    namespace = "au.com.ausroads.data.routes"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.kotlinx.datetime)
    implementation(libs.hilt.android)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
