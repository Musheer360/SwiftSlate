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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libxposed artifacts (also on Maven Central; this is the fallback mirror)
        maven("https://api.xposed.info/") {
            content { includeGroupByRegex("io\\.github\\.libxposed.*") }
        }
    }
}

rootProject.name = "SwiftSlate"
include(":app")

