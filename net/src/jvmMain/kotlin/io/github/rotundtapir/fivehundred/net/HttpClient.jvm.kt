// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

/** CIO engine — a pure-Kotlin/JVM stack that works on Android 7+ (minSdk 24) with no extra deps. */
actual fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(WebSockets)
}
