// build-logic — composite build for convention plugins.
// The :app and library modules consume these plugins via alias() in their build.gradle.kts.

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "build-logic"
include(":convention")
