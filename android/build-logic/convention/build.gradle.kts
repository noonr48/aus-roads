// build-logic convention plugins.
// Each plugin re-exports the AGP / Kotlin plugins with a consistent configuration so
// individual modules don't repeat boilerplate.
//
// We hardcode the AGP and Kotlin plugin artifact versions here (matching the parent
// project's version catalog) because this is a composite build; resolving the parent's
// libs catalog from a nested settings is fragile.

plugins {
    `kotlin-dsl`
}

group = "au.com.ausroads.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Plugin APIs are compileOnly — actual implementations come from the consuming module's
    // plugin classpath. The libraries are listed here so we can call extension functions
    // (e.g. androidExtension, kotlinExtension) inside the convention plugins.
    //
    // Versions are mirrored from /home/benbi/Apps/aus-roads/android/gradle/libs.versions.toml.
    // Keep these in sync when bumping AGP / Kotlin in the parent.
    compileOnly("com.android.tools.build:gradle:8.7.2")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    // Required by AndroidHiltConventionPlugin. KSP version MUST match Kotlin exactly
    // (2.0.21-1.0.27 in libs.versions.toml). Hilt 2.52 is the only Hilt version pinned
    // in the catalog.
    compileOnly("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.0.21-1.0.27")
    compileOnly("com.google.dagger:hilt-android-gradle-plugin:2.52")
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "ausroads.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "ausroads.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidHilt") {
            id = "ausroads.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "ausroads.android.application.compose"
            implementationClass = "AndroidApplicationComposeConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "ausroads.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        register("kotlinLibrary") {
            id = "ausroads.kotlin.library"
            implementationClass = "KotlinLibraryConventionPlugin"
        }
    }
}
