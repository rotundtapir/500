// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

// The wire protocol (shared by client and server) and the client-side WebSocket session. Pure
// Kotlin Multiplatform (jvm + wasmJs) so both the Android app and the browser build depend on the
// same types. FOSS-only (Ktor is Apache-2.0) — no proprietary dependency ever reaches this module,
// which is what keeps the F-Droid build graph clean once :shared consumes it.
kotlin {
    jvmToolchain(21)
    jvm()
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":engine"))
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit.jupiter)
            implementation(libs.kotlinx.coroutines.test)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Protocol code is mostly declarations plus a thin client; the golden/name tests cover the logic
// that matters. Start the ratchet modestly and raise it as behaviour lands.
kover {
    reports {
        verify {
            rule {
                minBound(70)
            }
        }
    }
}
