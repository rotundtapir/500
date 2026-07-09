// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    application
}

// The authoritative online-multiplayer server: a Ktor (CIO) WebSocket app that runs the same
// GameDriver the local app uses, one room per game. JVM-only — it depends on the pure-Kotlin engine,
// bot, and wire protocol. No Android, no Compose.
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":net"))
    implementation(project(":engine"))
    implementation(project(":ai"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(project(":ai"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("io.github.rotundtapir.fivehundred.server.MainKt")
    applicationDefaultJvmArgs = listOf("-Xms64m", "-Xmx256m", "-XX:MaxMetaspaceSize=96m")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Integration-heavy; line coverage is harder here than in the pure engine. Start modest, ratchet up.
kover {
    reports {
        verify {
            rule {
                minBound(60)
            }
        }
    }
}
