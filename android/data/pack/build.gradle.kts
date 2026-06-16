// :data:pack — Room database for installed map pack metadata.
// The actual tile/routing/search files live on the filesystem (mappacks/au-sa/<version>/);
// this module only stores the manifest + paths + size + installedAt. The MapPackManager
// (introduced in v0.1.1's downloader module) writes here on successful install.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.hilt")
}

android {
    namespace = "au.com.ausroads.data.pack"
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
    testImplementation(libs.androidx.room.testing)
}
