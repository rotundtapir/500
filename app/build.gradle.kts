// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Release signing: CI exports these as env vars from repo secrets (see .github/workflows/ci.yml);
// locally they can live in ~/.gradle/gradle.properties. Absent ⇒ unsigned release artifacts, which
// is right for local builds and for F-Droid (it signs with its own key).
fun secret(name: String): String? =
    providers.environmentVariable(name).orNull ?: providers.gradleProperty(name).orNull

val releaseKeystore: String? = secret("KEYSTORE_FILE")

android {
    namespace = "io.github.rotundtapir.fivehundred"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.rotundtapir.fivehundred"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Two distributions: `foss` (no ads, donation link — this is what F-Droid builds) and `play`
    // (Google Mobile Ads + a remove-ads purchase). Only `play` pulls in the proprietary module.
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            // Feedback goes to the public issue tracker — F-Droid users have GitHub, not Play.
            buildConfigField("String", "FEEDBACK_URI", "\"https://github.com/rotundtapir/500/issues\"")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "FEEDBACK_URI", "\"mailto:rotund_tapir@protonmail.com\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Deliberately not minified for 0.1: R8 failures only surface at runtime in release
            // builds, where test coverage is thinnest. Revisit with a QA pass once released.
            isMinifyEnabled = false
            // Null when no keystore is configured — the artifact is then unsigned.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    dependenciesInfo {
        // The AGP dependency block is encrypted with a Google-only key; F-Droid rejects APKs
        // carrying such an opaque blob. Play works fine without it.
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The shared multiplatform UI (screens + GameViewModel); brings engine/ai/cardkit with it.
    implementation(project(":shared"))

    // Shared cardkit modules (resolved from the submodule via the composite build).
    implementation(libs.cardkit.ui)
    implementation(libs.cardkit.monetization)
    // Proprietary ads/billing implementation ONLY in the play flavor. The foss flavor never links it.
    "playImplementation"(libs.cardkit.monetization.play)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // On-device integration tests (Compose UI driving a real game against the bots).
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
