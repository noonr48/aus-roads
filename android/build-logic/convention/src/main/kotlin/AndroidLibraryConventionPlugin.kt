// Applies the standard Android library + Kotlin configuration to a module.

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.android")
        }

        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            // Note: defaultConfig.targetSdk is deprecated in AGP 8.7+ for libraries.
            // Migrate to testOptions.targetSdk / lint.targetSdk once we ship library
            // modules that publish (v0.2+ with the traffic provider modules).
        }
    }
}
