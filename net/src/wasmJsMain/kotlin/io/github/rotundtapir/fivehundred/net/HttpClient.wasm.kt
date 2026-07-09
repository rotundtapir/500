// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets

/** The browser JS engine; its WebSocket support maps onto the platform `WebSocket`. */
actual fun defaultHttpClient(): HttpClient = HttpClient(Js) {
    install(WebSockets)
}
