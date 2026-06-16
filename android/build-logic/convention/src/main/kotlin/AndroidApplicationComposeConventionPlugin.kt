// Opt-in Compose for an Android APPLICATION module.
// Must be applied AFTER ausroads.android.application.

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidApplicationComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val catalogs = libs()

        with(pluginManager) {
            apply("org.jetbrains.kotlin.plugin.compose")
        }

        extensions.configure<ApplicationExtension> {
            buildFeatures {
                compose = true
            }
            dependencies {
                addComposeDependencies(catalogs)
            }
        }
    }
}
