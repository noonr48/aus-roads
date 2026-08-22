// :data:reports — Room database for community hazard reports (v0.5+ foundation).
// Pure persistence: no Compose, no Hilt annotations on the database itself.
// Hilt provides CommunityReportRepository via the @Inject-annotated
// RoomCommunityReportRepository in :app's ReportsModule.
// LOCAL-ONLY by design (docs/notes/privacy.md v0.5+): no networking here.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.hilt")
}

android {
    namespace = "au.com.ausroads.data.reports"
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
