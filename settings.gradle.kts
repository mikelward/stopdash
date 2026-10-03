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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // mikelward/androidlog publishes its own Maven repository into the
        // `maven` branch of that repository, which raw.githubusercontent.com
        // serves. A Maven repository is a static directory tree over HTTP, so
        // there is no third party in the trust path. Scoped to that one group.
        maven {
            name = "androidlog"
            url = uri("https://raw.githubusercontent.com/mikelward/androidlog/maven")
            content { includeGroup("com.mikelward.androidlog") }
        }
    }
}

rootProject.name = "StopDash"

include(":app")
include(":domain")
include(":shared")
include(":wear")
include(":lint-rules")
