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
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Kotlin/Wasm toolchain binaries (Node.js, Binaryen, Yarn). The Kotlin plugin normally
        // registers these ivy repositories per-project, but PREFER_SETTINGS ignores project
        // repositories, so they must be declared here.
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist") {
                    name = "Node.js dist"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/WebAssembly/binaryen/releases/download") {
                    name = "Binaryen dist"
                    patternLayout { artifact("version_[revision]/[module]-version_[revision]-[classifier].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.github.webassembly", "binaryen") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/yarnpkg/yarn/releases/download") {
                    name = "Yarn dist"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.yarnpkg", "yarn") }
        }
    }
}

rootProject.name = "five-hundred"

// Build the shared cardkit library from the git submodule. Gradle substitutes
// "io.github.rotundtapir.cardkit:<module>" dependencies with these local projects.
includeBuild("cardkit")

include(":engine")
include(":ai")
include(":net")
include(":server")
include(":shared")
include(":app")
include(":web")
