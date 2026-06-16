// Shared helpers for Android convention plugins.

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** Access the "libs" version catalog from a build-logic convention plugin. */
internal fun Project.libs(): VersionCatalog =
    extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Standard compileSdk / minSdk / Java toolchain config shared by app + library modules. */
internal fun Project.configureKotlinAndroid(
    extension: CommonExtension<*, *, *, *, *, *>,
) {
    val catalogs = libs()
    extension.apply {
        compileSdk = catalogs.findVersion("compileSdk").get().requiredVersion.toInt()
        defaultConfig {
            minSdk = catalogs.findVersion("minSdk").get().requiredVersion.toInt()
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
    configureKotlinCommon()
}

/** Configure Kotlin tasks for a JVM-target module (Android or pure Kotlin). */
internal fun Project.configureKotlinCommon() {
    tasks.withType(KotlinCompile::class.java).configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-Xjvm-default=all",
            )
        }
    }
}

/** Pure-Kotlin JVM target. Used by the :core:* modules. */
internal fun Project.configureJavaToolchain() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
