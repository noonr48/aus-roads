// :data:pins — Room database for user-created pins.
// Pure persistence: no Compose, no Hilt annotations on the database itself.
// Hilt provides PinRepository via the @Inject-annotated RoomPinRepository
// in :app's AppModule.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.hilt")
}

android {
    namespace = "au.com.ausroads.data.pins"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.hilt.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
}
