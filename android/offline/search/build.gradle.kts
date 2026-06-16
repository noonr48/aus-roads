// :offline:search — SQLite FTS5 search index accessor.
// Opens a pre-built search.db file and runs prefix queries.
// Does NOT use Room — the DB is built externally and shipped as a file.

plugins {
    id("ausroads.android.library")
    id("ausroads.android.hilt")
}

android {
    namespace = "au.com.ausroads.offline.search"
    defaultConfig {
        // Required so the on-device PoiBrowseInstrumentedTest is driven by the
        // androidx.test runner (the library convention plugin does not set one).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hilt.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented (on-device) tests. espresso-core pulls in androidx.test:runner
    // (the AndroidJUnitRunner class — ext.junit alone does NOT provide it, which
    // crashes instrumentation with ClassNotFoundException); ext.junit provides
    // AndroidJUnit4 + ApplicationProvider.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.truth)
}
