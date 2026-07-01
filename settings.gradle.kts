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
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "five-hundred"

// Build the shared cardkit library from the git submodule. Gradle substitutes
// "io.github.rotundtapir.cardkit:<module>" dependencies with these local projects.
includeBuild("cardkit")

// App modules are added by their respective commits:
//   include(":engine")
//   include(":ai")
//   include(":app")
