// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.cpd) // applied at the root for a single cross-module cpdCheck
}

// --- Quality gates (detekt + Compose rules); duplicated in the cardkit submodule's root build -----
subprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        // KMP has no src/main/kotlin, so the default source set is empty (NO-SOURCE). Point detekt
        // at the whole src tree so every source set (commonMain, androidMain, wasmJsMain, tests) is
        // analysed.
        source.setFrom(layout.projectDirectory.dir("src"))
        // No baseline: the tree is clean, so any new violation fails the gate.
    }
    dependencies {
        add("detektPlugins", rootProject.libs.compose.rules.detekt)
    }
}

// --- Duplication (PMD CPD) over Kotlin sources of every module ----------------------------------
cpd {
    language = "kotlin"
    toolVersion = libs.versions.pmd.get()
    minimumTokenCount = 100 // the default 50 is far too noisy for Kotlin
}
tasks.named<de.aaschmid.gradle.plugins.cpd.Cpd>("cpdCheck") {
    source = fileTree(rootDir) {
        include("**/src/**/*.kt") // every source set, KMP (commonMain…) and Android (main, flavors)
        // Ant-style patterns (no regex char classes): test source sets end in "Test".
        exclude("**/*Test/**", "**/test/**") // test duplication is expected and not worth flagging
        exclude("**/build/**")
        exclude("**/.claude/**") // stale scratch worktrees, not real sources
    }
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "Runs detekt across all modules plus the CPD duplication check."
    dependsOn(tasks.named("cpdCheck"))
    dependsOn(subprojects.map { "${it.path}:detekt" })
}
