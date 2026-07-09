// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
