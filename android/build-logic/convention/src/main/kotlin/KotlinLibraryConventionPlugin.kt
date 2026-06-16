// Pure-Kotlin / JVM library configuration. No Android dependencies.
// Use this for :core:model, :core:common and any other pure Kotlin module.

import org.gradle.api.Plugin
import org.gradle.api.Project

class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        with(pluginManager) {
            apply("org.jetbrains.kotlin.jvm")
        }
        configureJavaToolchain()
        configureKotlinCommon()
    }
}
