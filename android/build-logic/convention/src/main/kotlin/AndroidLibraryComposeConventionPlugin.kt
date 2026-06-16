// Opt-in Compose for an Android LIBRARY module.
// Must be applied AFTER ausroads.android.library.

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val catalogs = libs()

        with(pluginManager) {
            apply("org.jetbrains.kotlin.plugin.compose")
        }

        extensions.configure<LibraryExtension> {
            buildFeatures {
                compose = true
            }
            dependencies {
                addComposeDependencies(catalogs)
            }
        }
    }
}
