// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

// Pure Kotlin: the authoritative rules of Australian 500. No Android dependency, so it is fully
// unit-testable, runs in the browser (wasmJs), and could run server-side for online multiplayer.
kotlin {
    jvmToolchain(21)
    jvm()
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.cardkit.core)
            api(libs.kotlinx.serialization.core)
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

// Coverage ratchet for the pure rules engine — currently ~96% line coverage; the bound sits just
// below so it guards regressions without becoming a chore.
kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
