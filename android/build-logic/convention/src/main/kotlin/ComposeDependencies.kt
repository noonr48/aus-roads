// Shared helper for adding the Compose BOM + standard Compose dependencies.
// Used by both the application and library compose convention plugins.

import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.VersionCatalog

internal fun DependencyHandler.addComposeDependencies(catalogs: VersionCatalog) {
    val composeBom = catalogs.findLibrary("compose-bom").get()
    val bomDep = platform(composeBom)
    add("implementation", bomDep)
    add("androidTestImplementation", bomDep)

    add("implementation", catalogs.findLibrary("compose-ui").get())
    add("implementation", catalogs.findLibrary("compose-ui-graphics").get())
    add("implementation", catalogs.findLibrary("compose-ui-tooling-preview").get())
    add("implementation", catalogs.findLibrary("compose-material3").get())
    add("implementation", catalogs.findLibrary("compose-material-icons-extended").get())
    add("debugImplementation", catalogs.findLibrary("compose-ui-tooling").get())
}
