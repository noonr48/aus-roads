// aus-roads Gradle settings
// Project structure: single root, multi-module. See /home/benbi/Apps/aus-roads/docs/adr/0002-tech-stack.md

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "aus-roads"

include(":app")

// :core:* — pure-Kotlin domain + infrastructure
include(":core:model")
include(":core:common")
include(":core:geo")

// :traffic:* — live traffic provider system
include(":traffic:provider-api")
include(":traffic:provider-stub")
include(":traffic:provider-sa")
include(":traffic:provider-sa-outback")
include(":traffic:provider-vic")
include(":traffic:provider-nsw")
include(":traffic:congestion-api")

// :routing:* — routing engine abstraction (v0.4; API only in v0.1)
include(":routing:engine-api")
include(":routing:engine-valhalla")

// :offline:* — map pack contract + downloader + search
include(":offline:pack-api")
include(":offline:search")
include(":offline:pack-downloader")

// :ui:designsystem — shared Compose theme + components
include(":ui:designsystem")

// :feature:* — UI feature modules
include(":feature:search")
include(":feature:traffic")
include(":feature:navigation")
include(":feature:trip")

// :navigation:* — navigation subsystem
include(":navigation:tts")

// :data:* — persistence layer (Room + DataStore). v0.1.1: pins, settings, installed packs.
include(":data:pins")
include(":data:settings")
include(":data:pack")
include(":data:routes")
include(":data:tracks")

// :build-logic — convention plugins (not a runtime module)
includeBuild("build-logic")
