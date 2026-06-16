// Opt-in Hilt for an Android module. Applies the Hilt + KSP plugins, adds the
// hilt-android runtime and hilt-compiler (via KSP) dependencies. Must be applied
// AFTER ausroads.android.library (or ausroads.android.application). Both KSP and
// the Hilt gradle plugin must already be on the consuming module's classpath —
// the parent root build.gradle.kts declares them with `apply false`.
//
// The hilt-android *runtime* dependency is required by the Hilt Gradle plugin's
// bytecode transformer (it runs at compile time) — adding only the compiler via
// KSP is not enough. Hilt requires KSP (we are not using kapt in v0.1+). KSP
// version must match Kotlin exactly (2.0.21-1.0.27 in libs.versions.toml).

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        with(pluginManager) {
            apply("com.google.devtools.ksp")
            apply("com.google.dagger.hilt.android")
        }
        dependencies {
            // Runtime: required by the Hilt Gradle plugin's bytecode transformer.
            add("implementation", libs().findLibrary("hilt-android").get())
            // Compiler: runs via KSP at compile time.
            add("ksp", libs().findLibrary("hilt-compiler").get())
        }
    }
}
