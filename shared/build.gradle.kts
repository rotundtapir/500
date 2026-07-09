// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// The whole game UI (screens, GameViewModel, tutorial, settings surface), shared between the
// Android app (:app) and the browser build (:web). Platform specifics stay behind small seams:
// SettingsRepository (DataStore actual here in androidMain, localStorage in :web) and the
// cardkit-ui SoundManager.
kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":engine"))
            api(project(":ai"))
            api(project(":net"))
            api(libs.cardkit.ui)
            api(libs.cardkit.monetization)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // JetBrains' multiplatform androidx.lifecycle: ViewModel/viewModelScope/viewModel()
            // under the same package names as on Android.
            api(libs.jetbrains.lifecycle.viewmodel.compose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.datastore.preferences)
        }
        // Local JVM unit tests against the android target (no emulator): GameViewModel, the
        // tutorial-script gate, and settings logic use no Android framework APIs.
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "io.github.rotundtapir.fivehundred.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// TODO(KT-82989): revisit on Kotlin upgrade. The Compose compiler plugin emits top-level
// declarations, which Kotlin/JS+Wasm incremental compilation mishandles (KT-82395) — it can
// silently under-recompile a changed commonMain file, shipping a stale wasm binary (a green build
// over source it never recompiled). KT-82395 is marked fixed in 2.3.21-RC but the symptom persists
// here. The proper switch (Kotlin2JsCompile.incrementalJsKlib) is still `internal` — KT-82989 tracks
// exposing it — so until then we wipe this task's IC state before each run, forcing a correct full
// (non-incremental) wasm compile. Cheap for this module; drop this (and the matching block in
// web/build.gradle.kts) once KT-82989 ships a public per-task switch and the staleness is gone.
tasks.withType<Kotlin2JsCompile>().configureEach {
    val icStateDir = layout.buildDirectory.dir("kotlin/$name")
    doFirst { icStateDir.get().asFile.deleteRecursively() }
}
