// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
}

// Pure Kotlin heuristic bot for 500. Depends on the rules engine (which brings cardkit-core).
kotlin {
    jvmToolchain(21)
    jvm()
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":engine"))
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

// Coverage ratchet for the bot — currently ~92% line coverage.
kover {
    reports {
        verify {
            rule {
                minBound(85)
            }
        }
    }
}
