// aus-roads root build.gradle.kts.
// Individual module configuration is provided by convention plugins in :build-logic.

plugins {
    // AGP and Kotlin are declared here with `apply false` so the plugin IDs are
    // resolvable from the convention plugins in :build-logic. The convention
    // plugins call apply() on these in each consumer module.
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false

    // KSP and Hilt must be resolvable from the ausroads.android.hilt convention
    // plugin. Declared with apply false; the convention plugin applies them in
    // each consumer module. Version pinning: KSP tracks Kotlin's minor version.
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false

    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    source.setFrom(
        "app/src/main",
        // New flavor source sets carry DI modules; keep them linted. Other modules
        // (engine-valhalla, pack-downloader, offline/search, feature/*) stay at the
        // project's existing detekt posture — they predate this change and carry
        // intentional pre-existing complexity that's out of scope here.
        "app/src/offline",
        "app/src/withNetwork",
        "core/model/src/main",
        "core/common/src/main",
        "data/pins/src/main",
        "data/settings/src/main",
        "data/pack/src/main",
        "traffic/provider-api/src/main",
        "traffic/provider-stub/src/main",
        "traffic/provider-sa/src/main",
        "routing/engine-api/src/main",
        "offline/pack-api/src/main",
        "ui/designsystem/src/main",
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
